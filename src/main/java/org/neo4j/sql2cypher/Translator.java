/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.sql2cypher;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jooq.Asterisk;
import org.jooq.CreateTableElementListStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Param;
import org.jooq.Parser;
import org.jooq.QualifiedAsterisk;
import org.jooq.Query;
import org.jooq.QueryPart;
import org.jooq.Row;
import org.jooq.Select;
import org.jooq.SelectField;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.conf.ParseWithMetaLookups;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.QOM;
import org.jooq.impl.QOM.TableAlias;
import org.neo4j.cypherdsl.core.Case;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.ExposesRelationships;
import org.neo4j.cypherdsl.core.Expression;
import org.neo4j.cypherdsl.core.Functions;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.PatternElement;
import org.neo4j.cypherdsl.core.Relationship;
import org.neo4j.cypherdsl.core.RelationshipChain;
import org.neo4j.cypherdsl.core.ResultStatement;
import org.neo4j.cypherdsl.core.SortItem;
import org.neo4j.cypherdsl.core.Statement;
import org.neo4j.cypherdsl.core.StatementBuilder;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithWhere;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithoutWhere;
import org.neo4j.cypherdsl.core.renderer.Configuration;
import org.neo4j.cypherdsl.core.renderer.Renderer;

/**
 * Quick proof of concept of a jOOQ/Cypher-DSL based SQL to Cypher translator.
 *
 * @author Lukas Eder
 * @author Michael J. Simons
 * @author Michael Hunger
 */
public final class Translator {

	public static Translator defaultTranslator() {
		return new Translator(TranslatorConfig.defaultConfig());
	}

	public static Translator with(TranslatorConfig config) {
		return new Translator(config);
	}

	private final TranslatorConfig config;

	private Translator(TranslatorConfig config) {

		this.config = config;
	}

	// Unsure how thread safe this should be (wrt the node lookup table), but this here
	// will do for the purpose of adding some tests
	public String convert(String sql) {
		Query query = parse(sql);

		if (query instanceof Select<?> s) {
			return render(statement(s));
		}
		else if (query instanceof QOM.Delete<?> d) {
			return render(statement(d));
		}
		else if (query instanceof QOM.Truncate<?> t) {
			return render(statement(t));
		}
		else if (query instanceof QOM.Insert<?> t) {
			return render(statement(t));
		}
		else {
			throw unsupported(query);
		}
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private DSLContext createDSLContext() {

		var settings = new DefaultConfiguration().settings().withParseNameCase(this.config.getParseNameCase())
				.withRenderNameCase(this.config.getRenderNameCase())
				.withParseWithMetaLookups(ParseWithMetaLookups.IGNORE_ON_FAILURE).withDiagnosticsLogging(true)
				.withParseDialect(this.config.getSqlDialect());

		Optional.ofNullable(this.config.getParseNamedParamPrefix()).filter(Predicate.not(String::isBlank))
				.map(String::trim).ifPresent(settings::withParseNamedParamPrefix);

		var context = DSL.using(this.config.getSqlDialect(), settings);
		context.configuration().set(() -> {
			var tables = new HashMap<String, Query>();

			this.config.getJoinColumnsToTypeMappings().forEach((k, v) -> {
				var tableAndColumnName = k.split("\\.");
				var createTableStep = (CreateTableElementListStep) tables.computeIfAbsent(tableAndColumnName[0],
						DSL::createTable);
				createTableStep.column(DSL.field(tableAndColumnName[1]).comment("type=" + v));
			});

			this.config.getTableToLabelMappings().forEach((k, v) -> {
				var createTableStep = (CreateTableElementListStep) tables.computeIfAbsent(k, DSL::createTable);
				createTableStep.comment("label=" + v);
			});

			return context.meta(tables.values().toArray(Query[]::new));
		});
		return context;
	}

	private Query parse(String sql) {
		DSLContext dsl = createDSLContext();
		Parser parser = dsl.parser();
		return parser.parseQuery(sql);
	}

	private String render(Statement statement) {
		Renderer renderer = Renderer.getRenderer(
				this.config.isPrettyPrint() ? Configuration.prettyPrinting() : Configuration.defaultConfig());
		return renderer.render(statement);
	}

	Statement statement(QOM.Delete<?> d) {
		Node e = (Node) resolveTableOrJoin(d.$from());

		OngoingReadingWithoutWhere m1 = Cypher.match(e);
		OngoingReadingWithWhere m2 = (d.$where() != null) ? m1.where(condition(d.$where()))
				: (OngoingReadingWithWhere) m1;
		return m2.delete(e.asExpression()).build();
	}

	Statement statement(QOM.Truncate<?> t) {
		Node e = (Node) resolveTableOrJoin(t.$table());

		return Cypher.match(e).detachDelete(e.asExpression()).build();
	}

	ResultStatement statement(Select<?> x) {

		// Done lazy as otherwise the property containers won't be resolved
		Supplier<List<Expression>> resultColumnsSupplier = () -> x.$select().stream()
				.map((t) -> (Expression) expression(t)).toList();

		if (x.$from().isEmpty()) {
			return Cypher.returning(resultColumnsSupplier.get()).build();
		}

		OngoingReadingWithoutWhere m1 = Cypher.match(x.$from().stream().map(this::resolveTableOrJoin).toList());

		OngoingReadingWithWhere m2 = (x.$where() != null) ? m1.where(condition(x.$where()))
				: (OngoingReadingWithWhere) m1;

		var returning = m2.returning(resultColumnsSupplier.get())
				.orderBy(x.$orderBy().stream().map(this::expression).toList());

		StatementBuilder.BuildableStatement<ResultStatement> buildableStatement;
		if (!(x.$limit() instanceof Param<?> param)) {
			buildableStatement = returning;
		}
		else {
			buildableStatement = returning.limit(expression(param));
		}

		return buildableStatement.build();
	}

	Statement statement(QOM.Insert<?> insert) {
		var table = insert.$into();
		// TODO handle if this resolves to something unexpectedly different
		var node = (Node) this.resolveTableOrJoin(table);

		var rows = insert.$values();
		var columns = insert.$columns();

		if (rows.size() == 1) {
			Object[] keysAndValues = new Object[columns.size() * 2];
			var row = rows.get(0);
			for (int i = 0; i < columns.size(); ++i) {
				keysAndValues[i * 2] = columns.get(i).getName();
				keysAndValues[i * 2 + 1] = expression(row.field(i));
			}
			return Cypher.create(node.withProperties(keysAndValues)).build();
		}
		else {
			var props = insert.$values().stream().map(row -> {
				var result = new HashMap<String, Object>(columns.size());
				for (int i = 0; i < columns.size(); ++i) {
					result.put(columns.get(i).getName(), expression(row.field(i)));
				}
				return Cypher.literalOf(result);
			}).toList();
			return Cypher.unwind(Cypher.listOf(props)).as("properties").create(node)
					.set(node, Cypher.name("properties")).build();
		}
	}

	private Expression expression(SelectFieldOrAsterisk t) {
		if (t instanceof SelectField<?> s) {
			return expression(s);
		}
		else if (t instanceof Asterisk) {
			return Cypher.asterisk();
		}
		else if (t instanceof QualifiedAsterisk q && resolveTableOrJoin(q.$table()) instanceof Node node) {
			return node.getSymbolicName().orElseGet(() -> Cypher.name(q.$table().getName()));
		}
		else {
			throw unsupported(t);
		}
	}

	private Expression expression(SelectField<?> s) {
		if (s instanceof QOM.FieldAlias<?> fa) {
			return expression(fa.$aliased()).as(fa.$alias().last());
		}
		else if (s instanceof Field<?> f) {
			return expression(f);
		}
		else {
			throw unsupported(s);
		}
	}

	private SortItem expression(SortField<?> s) {
		return Cypher.sort(expression(s.$field()),
				SortItem.Direction.valueOf(s.$sortOrder().name().toUpperCase(Locale.ROOT)));
	}

	private Expression expression(Field<?> f) {
		if (f instanceof Param<?> p) {
			if (p.$inline()) {
				return Cypher.literalOf(p.getValue());
			}
			else if (p.getParamName() != null) {
				return Cypher.parameter(p.getParamName(), p.getValue());
			}
			else {
				return Cypher.anonParameter(p.getValue());
			}
		}
		else if (f instanceof TableField<?, ?> tf && tf.getTable() != null) {
			var pe = resolveTableOrJoin(tf.getTable());
			if (pe instanceof Node node) {
				return node.property(tf.getName());
			}
			else if (pe instanceof Relationship rel) {
				return rel.property(tf.getName());
			}
			else {
				throw unsupported(tf);
			}
		}
		else if (f instanceof QOM.Add<?> e) {
			return expression(e.$arg1()).add(expression(e.$arg2()));
		}
		else if (f instanceof QOM.Sub<?> e) {
			return expression(e.$arg1()).subtract(expression(e.$arg2()));
		}
		else if (f instanceof QOM.Mul<?> e) {
			return expression(e.$arg1()).multiply(expression(e.$arg2()));
		}
		else if (f instanceof QOM.Square<?> e) {
			return expression(e.$arg1()).multiply(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Div<?> e) {
			return expression(e.$arg1()).divide(expression(e.$arg2()));
		}
		else if (f instanceof QOM.Neg<?> e) {
			throw unsupported(e);
		}

		// https://neo4j.com/docs/cypher-manual/current/functions/mathematical-numeric/
		else if (f instanceof QOM.Abs<?> e) {
			return org.neo4j.cypherdsl.core.Functions.abs(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Ceil<?> e) {
			return org.neo4j.cypherdsl.core.Functions.ceil(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Floor<?> e) {
			return org.neo4j.cypherdsl.core.Functions.floor(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Round<?> e) {
			if (e.$arg2() == null) {
				return org.neo4j.cypherdsl.core.Functions.round(expression(e.$arg1()));
			}
			else {
				return org.neo4j.cypherdsl.core.Functions.round(expression(e.$arg1()), expression(e.$arg2()));
			}
		}
		else if (f instanceof QOM.Sign e) {
			return org.neo4j.cypherdsl.core.Functions.sign(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Rand) {
			return Functions.rand();
		}

		// https://neo4j.com/docs/cypher-manual/current/functions/mathematical-logarithmic/
		else if (f instanceof QOM.Euler) {
			return Functions.e();
		}
		else if (f instanceof QOM.Exp e) {
			return Functions.exp(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Ln e) {
			return org.neo4j.cypherdsl.core.Functions.log(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Log e) {
			return org.neo4j.cypherdsl.core.Functions.log(expression(e.$arg1()))
					.divide(org.neo4j.cypherdsl.core.Functions.log(expression(e.$arg2())));
		}
		else if (f instanceof QOM.Log10 e) {
			return org.neo4j.cypherdsl.core.Functions.log10(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Sqrt e) {
			return org.neo4j.cypherdsl.core.Functions.sqrt(expression(e.$arg1()));
		}
		// TODO: Hyperbolic functions

		// https://neo4j.com/docs/cypher-manual/current/functions/mathematical-trigonometric/
		else if (f instanceof QOM.Acos e) {
			return org.neo4j.cypherdsl.core.Functions.acos(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Asin e) {
			return org.neo4j.cypherdsl.core.Functions.asin(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Atan e) {
			return org.neo4j.cypherdsl.core.Functions.atan(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Atan2 e) {
			return org.neo4j.cypherdsl.core.Functions.atan2(expression(e.$arg1()), expression(e.$arg2()));
		}
		else if (f instanceof QOM.Cos e) {
			return org.neo4j.cypherdsl.core.Functions.cos(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Cot e) {
			return org.neo4j.cypherdsl.core.Functions.cot(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Degrees e) {
			return org.neo4j.cypherdsl.core.Functions.degrees(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Pi) {
			return Functions.pi();
		}
		else if (f instanceof QOM.Radians e) {
			return org.neo4j.cypherdsl.core.Functions.radians(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Sin e) {
			return org.neo4j.cypherdsl.core.Functions.sin(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Tan e) {
			return org.neo4j.cypherdsl.core.Functions.tan(expression(e.$arg1()));
		}

		// https://neo4j.com/docs/cypher-manual/current/functions/string/
		else if (f instanceof QOM.CharLength e) {
			return Functions.size(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Left e) {
			return Functions.left(expression(e.$arg1()), expression(e.$arg2()));
		}
		else if (f instanceof QOM.Lower e) {
			return org.neo4j.cypherdsl.core.Functions.toLower(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Ltrim e) {
			return org.neo4j.cypherdsl.core.Functions.ltrim(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Replace e) {
			return Functions.replace(expression(e.$arg1()), expression(e.$arg2()), expression(e.$arg3()));
		}
		else if (f instanceof QOM.Reverse e) {
			return Functions.reverse(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Right e) {
			return Functions.right(expression(e.$arg1()), expression(e.$arg2()));
		}
		else if (f instanceof QOM.Rtrim e) {
			return org.neo4j.cypherdsl.core.Functions.rtrim(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Substring e) {
			var length = expression(e.$arg3());
			if (length != Cypher.literalNull()) {
				return org.neo4j.cypherdsl.core.Functions.substring(expression(e.$arg1()), expression(e.$arg2()),
						length);
			}
			else {
				return org.neo4j.cypherdsl.core.Functions.substring(expression(e.$arg1()), expression(e.$arg2()), null);
			}
		}
		else if (f instanceof QOM.Trim e) {
			if (e.$arg2() != null) {
				throw unsupported(e);
			}
			else {
				return Functions.trim(expression(e.$arg1()));
			}
		}
		else if (f instanceof QOM.Upper e) {
			return Functions.toUpper(expression(e.$arg1()));
		}

		// https://neo4j.com/docs/cypher-manual/current/functions/scalar/
		else if (f instanceof QOM.Coalesce<?> e) {
			return Functions.coalesce(e.$arg1().stream().map(this::expression).toArray(Expression[]::new));
		}
		else if (f instanceof QOM.Nvl<?> e) {
			return Functions.coalesce(expression(e.$arg1()), expression(e.$arg2()));
		}

		// https://neo4j.com/docs/cypher-manual/current/syntax/expressions/
		else if (f instanceof QOM.Nullif<?> e) {
			return Cypher.caseExpression().when(expression(e.$arg1()).eq(expression(e.$arg2())))
					.then(Cypher.literalNull()).elseDefault(expression(e.$arg1()));
		}
		else if (f instanceof QOM.Nvl2<?> e) {
			return Cypher.caseExpression().when(expression(e.$arg1()).isNotNull()).then(expression(e.$arg2()))
					.elseDefault(expression(e.$arg3()));
		}
		else if (f instanceof QOM.CaseSimple<?, ?> e) {
			Case c = Cypher.caseExpression(expression(e.$value()));

			for (var w : e.$when()) {
				c = c.when(expression(w.$1())).then(expression(w.$2()));
			}

			if (e.$else() != null) {
				c = ((Case.CaseEnding) c).elseDefault(expression(e.$else()));
			}

			return c;
		}
		else if (f instanceof QOM.CaseSearched<?> e) {
			Case c = Cypher.caseExpression();

			for (var w : e.$when()) {
				c = c.when(condition(w.$1())).then(expression(w.$2()));
			}

			if (e.$else() != null) {
				c = ((Case.CaseEnding) c).elseDefault(expression(e.$else()));
			}

			return c;
		}

		// Others
		else if (f instanceof QOM.Cast<?> e) {
			if (e.$dataType().isString()) {
				return Functions.toString(expression(e.$field()));
			}
			else if (e.$dataType().isBoolean()) {
				return Functions.toBoolean(expression(e.$field()));
			}
			else if (e.$dataType().isFloat()) {
				return Functions.toFloat(expression(e.$field()));
			}
			else if (e.$dataType().isInteger()) {
				return Functions.toInteger(expression(e.$field()));
			}
			else {
				throw unsupported(f);
			}
		}
		else if (f instanceof org.jooq.True) {
			return Cypher.literalTrue();
		}
		else if (f instanceof org.jooq.False) {
			return Cypher.literalFalse();
		}
		else if (f instanceof QOM.Null || f == null || f instanceof org.jooq.Null) {
			return Cypher.literalNull();
		}
		else {
			throw unsupported(f);
		}
	}

	private IllegalArgumentException unsupported(QueryPart p) {
		return new IllegalArgumentException("Unsupported SQL expression: " + p);
	}

	private <T> Condition condition(org.jooq.Condition c) {
		if (c instanceof QOM.And a) {
			return condition(a.$arg1()).and(condition(a.$arg2()));
		}
		else if (c instanceof QOM.Or o) {
			return condition(o.$arg1()).or(condition(o.$arg2()));
		}
		else if (c instanceof QOM.Xor o) {
			return condition(o.$arg1()).xor(condition(o.$arg2()));
		}
		else if (c instanceof QOM.Not o) {
			return condition(o.$arg1()).not();
		}
		else if (c instanceof QOM.Eq<?> e) {
			return expression(e.$arg1()).eq(expression(e.$arg2()));
		}
		else if (c instanceof QOM.Gt<?> e) {
			return expression(e.$arg1()).gt(expression(e.$arg2()));
		}
		else if (c instanceof QOM.Ge<?> e) {
			return expression(e.$arg1()).gte(expression(e.$arg2()));
		}
		else if (c instanceof QOM.Lt<?> e) {
			return expression(e.$arg1()).lt(expression(e.$arg2()));
		}
		else if (c instanceof QOM.Le<?> e) {
			return expression(e.$arg1()).lte(expression(e.$arg2()));
		}
		else if (c instanceof QOM.Between<?> e) {
			if (e.$symmetric()) {
				@SuppressWarnings("unchecked")
				QOM.Between<T> t = (QOM.Between<T>) e;
				return condition(t.$symmetric(false))
						.or(condition(t.$symmetric(false).$arg2(t.$arg3()).$arg3(t.$arg2())));
			}
			else {
				return expression(e.$arg2()).lte(expression(e.$arg1()))
						.and(expression(e.$arg1()).lte(expression(e.$arg3())));
			}
		}
		else if (c instanceof QOM.Ne<?> e) {
			return expression(e.$arg1()).ne(expression(e.$arg2()));
		}
		else if (c instanceof QOM.IsNull e) {
			return expression(e.$arg1()).isNull();
		}
		else if (c instanceof QOM.IsNotNull e) {
			return expression(e.$arg1()).isNotNull();
		}
		else if (c instanceof QOM.RowEq<?> e) {
			Condition result = null;

			for (int i = 0; i < e.$arg1().size(); i++) {
				Condition r = expression(e.$arg1().field(i)).eq(expression(e.$arg2().field(i)));
				result = (result != null) ? result.and(r) : r;
			}

			return result;
		}
		else if (c instanceof QOM.RowNe<?> e) {
			Condition result = null;

			for (int i = 0; i < e.$arg1().size(); i++) {
				Condition r = expression(e.$arg1().field(i)).ne(expression(e.$arg2().field(i)));
				result = (result != null) ? result.and(r) : r;
			}

			return result;
		}
		else if (c instanceof QOM.RowGt<?> e) {
			return rowCondition(e.$arg1(), e.$arg2(), Expression::gt, Expression::gt);
		}
		else if (c instanceof QOM.RowGe<?> e) {
			return rowCondition(e.$arg1(), e.$arg2(), Expression::gt, Expression::gte);
		}
		else if (c instanceof QOM.RowLt<?> e) {
			return rowCondition(e.$arg1(), e.$arg2(), Expression::lt, Expression::lt);
		}
		else if (c instanceof QOM.RowLe<?> e) {
			return rowCondition(e.$arg1(), e.$arg2(), Expression::lt, Expression::lte);
		}
		else if (c instanceof QOM.RowIsNull e) {
			return e.$arg1().$fields().stream().map(f -> expression(f).isNull()).reduce(Condition::and).get();
		}
		else if (c instanceof QOM.RowIsNotNull e) {
			return e.$arg1().$fields().stream().map(f -> expression(f).isNotNull()).reduce(Condition::and).get();
		}
		else {
			throw unsupported(c);
		}
	}

	private Condition rowCondition(Row r1, Row r2,
			BiFunction<? super Expression, ? super Expression, ? extends Condition> comp,
			BiFunction<? super Expression, ? super Expression, ? extends Condition> last) {
		Condition result = last.apply(expression(r1.field(r1.size() - 1)), expression(r2.field(r1.size() - 1)));

		for (int i = r1.size() - 2; i >= 0; i--) {
			Expression e1 = expression(r1.field(i));
			Expression e2 = expression(r2.field(i));
			result = comp.apply(e1, e2).or(e1.eq(e2).and(result));
		}

		return result;
	}

	private PatternElement resolveTableOrJoin(Table<?> t) {
		if (t instanceof QOM.Join<?> join && join.$on() instanceof QOM.Eq<?> eq) {

			String relType;
			String relSymbolicName = null;

			PatternElement lhs;
			PatternElement rhs;

			if (join.$table1() instanceof QOM.Join<?> lhsJoin) {
				lhs = resolveTableOrJoin(lhsJoin.$table1());
				relType = labelOrType(lhsJoin.$table2());
				if (lhsJoin.$table2() instanceof TableAlias<?> tableAlias) {
					relSymbolicName = tableAlias.getName();
				}
			}
			else {
				lhs = resolveTableOrJoin(join.$table1());
				relType = relationshipTypeName(eq.$arg2());
			}

			rhs = resolveTableOrJoin(join.$table2());

			if (lhs instanceof ExposesRelationships<?> from && rhs instanceof Node to) {

				var direction = Relationship.Direction.LTR;
				if (join.$table2() instanceof TableAlias<?> ta
						&& ta.$alias().last().equals(eq.$arg2().getQualifiedName().first())) {
					direction = Relationship.Direction.RTL;
				}

				var relationship = from.relationshipWith(to, direction, relType);
				if (relSymbolicName != null) {
					if (relationship instanceof Relationship r) {
						relationship = r.named(relSymbolicName);
					}
					else if (relationship instanceof RelationshipChain r) {
						relationship = r.named(relSymbolicName);
					}
				}
				return relationship;
			}
			else {
				throw unsupported(join);
			}
		}

		if (t instanceof TableAlias<?> ta) {
			if (resolveTableOrJoin(ta.$aliased()) instanceof Node) {
				return Cypher.node(labelOrType(ta.$aliased())).named(ta.$alias().last());
			}
			else {
				throw unsupported(ta);
			}
		}
		else {
			return Cypher.node(labelOrType(t)).named(t.getName());
		}
	}

	private String labelOrType(Table<?> tableOrAlias) {

		var t = (tableOrAlias instanceof TableAlias<?> ta) ? ta.$aliased() : tableOrAlias;
		var comment = t.getComment();
		if (!comment.isBlank()) {
			var config = Arrays.stream(comment.split(",")).map((s) -> s.split("="))
					.collect(Collectors.toMap((a) -> a[0], (a) -> a[1]));
			return config.getOrDefault("label", t.getName());
		}

		return t.getName();
	}

	private String relationshipTypeName(Field<?> lhsJoinColumn) {

		return Objects.requireNonNull(lhsJoinColumn.getQualifiedName().last()).toUpperCase(Locale.ROOT);
	}

}
