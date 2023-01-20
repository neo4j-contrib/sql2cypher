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
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jooq.*;
import org.jooq.Asterisk;
import org.jooq.conf.ParseNameCase;
import org.jooq.conf.ParseWithMetaLookups;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.QOM;
import org.jooq.impl.QOM.TableAlias;

import org.neo4j.cypherdsl.core.*;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Functions;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithWhere;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithoutWhere;
import org.neo4j.cypherdsl.core.renderer.Configuration;
import org.neo4j.cypherdsl.core.renderer.Renderer;

import static org.jooq.impl.DSL.createTable;
import static org.neo4j.cypherdsl.core.Functions.*;

/**
 * Quick proof of concept of a jOOQ/Cypher-DSL based SQL to Cypher translator
 * @author Lukas Eder
 * @author Michael J. Simons
 */
public class SQLToCypher {

    public static void main(String[] args) {
        System.out.println(SQLToCypher.withoutMappings().convert("""
            SELECT t.a, t.b
            FROM my_table AS t
            WHERE t.a = 1
            """));
    }

    private final Config config;

    public static SQLToCypher withoutMappings(){
        return new SQLToCypher(new Config());
    }

    public static SQLToCypher with(Map<String, String> tableMappings) {
        return new SQLToCypher(new Config().with(tableMappings));
    }
    public static SQLToCypher with(Config config) {
        return new SQLToCypher(config);
    }

    public static class Config {
        private final Settings jooqSettings;
        private final Map<String,String> tableToLabelMappings;
        private final SQLDialect sqlDialect;
        private final Configuration cypherDslConfig;

        public Config() {
            this(Map.of(), defaultSettings(), SQLDialect.DEFAULT, Configuration.prettyPrinting());
        }

        public Config(Map<String, String> tableToLabelMappings, Settings jooqSettings, SQLDialect sqlDialect, Configuration cypherDslConfig) {
            this.jooqSettings = jooqSettings;
            this.tableToLabelMappings = tableToLabelMappings;
            this.sqlDialect = sqlDialect;
            this.cypherDslConfig = cypherDslConfig;
        }

        public Config with(Map<String, String> tableMappings) {
            return new Config(tableMappings, this.jooqSettings, this.sqlDialect, this.cypherDslConfig);
        }

        private static Settings defaultSettings() {
            return new DefaultConfiguration().settings()
                    .withParseNameCase(ParseNameCase.LOWER_IF_UNQUOTED) // Should be configurable
                    .withRenderNameCase(RenderNameCase.LOWER)
                    .withParseWithMetaLookups(ParseWithMetaLookups.IGNORE_ON_FAILURE)
                    .withDiagnosticsLogging(true);
        }

        private Settings getJooqSettings() {
            return jooqSettings.withParseDialect(sqlDialect);
        }

        private Map<String, String> getTableToLabelMappings() {
            return tableToLabelMappings;
        }

        private SQLDialect getSqlDialect() {
            return sqlDialect;
        }

        private Configuration getCypherDslConfig() {
            return cypherDslConfig;
        }
    }
    private final Map<Table<?>, Node> tables = new HashMap<>();

    private SQLToCypher(Config config) {
        this.config = config;
    }

    private DSLContext configure(Config config) {
        Settings jooqConfig = config.getJooqSettings();
        DSLContext dsl = DSL.using(config.getSqlDialect(), jooqConfig);
        dsl.configuration().set(() -> {
            var queries = config.getTableToLabelMappings().entrySet()
                    .stream()
                    .map(e -> (Query) createTable(e.getKey()).comment("label=" + e.getValue()))
                    .toList();
            return dsl.meta(queries.toArray(Query[]::new));
        });
        return dsl;
    }

    // Unsure how thread safe this should be (wrt the node lookup table), but this here will do for the purpose of adding some tests
    public String convert(String sql) {
        Select<?> query = parse(sql);
        ResultStatement statement = statement(query);
        return render(statement);
    }

    String render(ResultStatement statement) {
        Renderer renderer = Renderer.getRenderer(config.cypherDslConfig);
        return renderer.render(statement);
    }

    private Select<?> parse(String sql) {
        DSLContext dsl = configure(config);
        Parser parser = dsl.parser();
        return parser.parseSelect(sql);
    }

    ResultStatement statement(Select<?> x) {

        // Done lazy as otherwise the property containers won't be resolved
        Supplier<List<Expression>> resultColumnsSupplier = () -> x.$select().stream().map(t -> (Expression) expression(t)).toList();

        if (x.$from().isEmpty()) {
            return Cypher.returning(resultColumnsSupplier.get()).build();
        }

        OngoingReadingWithoutWhere m1 = Cypher
            .match(x.$from().stream().map(t -> {
                Node node = node(t);
                tables.put(t, node);
                return (PatternElement) node;
            }).toList());

        OngoingReadingWithWhere m2 = x.$where() != null
             ? m1.where(condition(x.$where()))
             : (OngoingReadingWithWhere) m1;

        var returning = m2.returning(resultColumnsSupplier.get())
            .orderBy(x.$orderBy().stream().map(this::expression).toList());


        StatementBuilder.BuildableStatement<ResultStatement> buildableStatement;
        if (!(x.$limit() instanceof Param<?> param)) {
            buildableStatement = returning;
        } else {
            buildableStatement = returning.limit(expression(param));
        }

        return buildableStatement.build();
    }

    private Expression expression(SelectFieldOrAsterisk t) {
        if (t instanceof SelectField<?> s) {
            return expression(s);
        } else if(t instanceof Asterisk) {
            return Cypher.asterisk();
        } else if(t instanceof QualifiedAsterisk q){
            return node(q.$table()).getSymbolicName().orElseGet(() -> Cypher.name(q.$table().getName()));
        } else {
            throw new IllegalArgumentException("unsupported: " + t);
        }
    }

    private Expression expression(SelectField<?> s) {
        if (s instanceof QOM.FieldAlias<?> fa)
            return expression(fa.$aliased()).as(fa.$alias().last());
        else if (s instanceof Field<?> f)
            return expression(f);
        else
            throw new IllegalArgumentException("unsupported: " + s);
    }

    private SortItem expression(SortField<?> s) {
        return Cypher.sort(expression(s.$field()), SortItem.Direction.valueOf(s.$sortOrder().name().toUpperCase(Locale.ROOT)));
    }

    private Expression expression(Field<?> f) {
        if (f instanceof Param<?> p)
            if (p.$inline())
                return Cypher.literalOf(p.getValue());
            else if (p.getParamName() != null)
                return Cypher.parameter(p.getParamName(), p.getValue());
            else
                return Cypher.anonParameter(p.getValue());
        else if (f instanceof TableField<?, ?> tf)
            return lookupNode(tf.getTable()).property(tf.getName());
        else if (f instanceof QOM.Add<?> e)
            return expression(e.$arg1()).add(expression(e.$arg2()));
        else if (f instanceof QOM.Sub<?> e)
            return expression(e.$arg1()).subtract(expression(e.$arg2()));
        else if (f instanceof QOM.Mul<?> e)
            return expression(e.$arg1()).multiply(expression(e.$arg2()));
        else if (f instanceof QOM.Square<?> e)
            return expression(e.$arg1()).multiply(expression(e.$arg1()));
        else if (f instanceof QOM.Div<?> e)
            return expression(e.$arg1()).divide(expression(e.$arg2()));
        else if (f instanceof QOM.Neg<?> e)
            throw new IllegalArgumentException("unsupported: " + f);

        // https://neo4j.com/docs/cypher-manual/current/functions/mathematical-numeric/
        else if (f instanceof QOM.Abs<?> e)
            return abs(expression(e.$arg1()));
        else if (f instanceof QOM.Ceil<?> e)
            return ceil(expression(e.$arg1()));
        else if (f instanceof QOM.Floor<?> e)
            return floor(expression(e.$arg1()));
        else if (f instanceof QOM.Round<?> e)
            if (e.$arg2() == null)
                return round(expression(e.$arg1()));
            else
                return round(expression(e.$arg1()), expression(e.$arg2()));
        else if (f instanceof QOM.Sign e)
            return sign(expression(e.$arg1()));
        else if (f instanceof QOM.Rand e)
            return Functions.rand();

        // https://neo4j.com/docs/cypher-manual/current/functions/mathematical-logarithmic/
        else if (f instanceof QOM.Euler e)
            return Functions.e();
        else if (f instanceof QOM.Exp e)
            return Functions.exp(expression(e.$arg1()));
        else if (f instanceof QOM.Ln e)
            return log(expression(e.$arg1()));
        else if (f instanceof QOM.Log e)
            return log(expression(e.$arg1())).divide(log(expression(e.$arg2())));
        else if (f instanceof QOM.Log10 e)
            return log10(expression(e.$arg1()));
        else if (f instanceof QOM.Sqrt e)
            return sqrt(expression(e.$arg1()));
        // TODO: Hyperbolic functions

        // https://neo4j.com/docs/cypher-manual/current/functions/mathematical-trigonometric/
        else if (f instanceof QOM.Acos e)
            return acos(expression(e.$arg1()));
        else if (f instanceof QOM.Asin e)
            return asin(expression(e.$arg1()));
        else if (f instanceof QOM.Atan e)
            return atan(expression(e.$arg1()));
        else if (f instanceof QOM.Atan2 e)
            return atan2(expression(e.$arg1()), expression(e.$arg2()));
        else if (f instanceof QOM.Cos e)
            return cos(expression(e.$arg1()));
        else if (f instanceof QOM.Cot e)
            return cot(expression(e.$arg1()));
        else if (f instanceof QOM.Degrees e)
            return degrees(expression(e.$arg1()));
        else if (f instanceof QOM.Pi)
            return Functions.pi();
        else if (f instanceof QOM.Radians e)
            return radians(expression(e.$arg1()));
        else if (f instanceof QOM.Sin e)
            return sin(expression(e.$arg1()));
        else if (f instanceof QOM.Tan e)
            return tan(expression(e.$arg1()));

        // https://neo4j.com/docs/cypher-manual/current/functions/string/
        else if (f instanceof QOM.Cast<?> e)
            if (e.$dataType().isString())
                return Functions.toString(expression(e.$field()));
            else
                throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Left e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Lower e)
            return toLower(expression(e.$arg1()));
        else if (f instanceof QOM.Ltrim e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Replace e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Reverse e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Right e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Rtrim e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Substring e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);
        else if (f instanceof QOM.Trim e)
            if (e.$arg2() != null)
                throw new IllegalArgumentException("unsupported: " + f);
            else
                return Functions.trim(expression(e.$arg1()));
        else if (f instanceof QOM.Upper e)
            // TODO: Support this in Cypher-DSL
            throw new IllegalArgumentException("unsupported: " + f);

        else
            throw new IllegalArgumentException("unsupported: " + f);
    }

    private Condition condition(org.jooq.Condition c) {
        if (c instanceof QOM.And a)
            return condition(a.$arg1()).and(condition(a.$arg2()));
        else if (c instanceof QOM.Or o)
            return condition(o.$arg1()).or(condition(o.$arg2()));
        else if (c instanceof QOM.Eq<?> e)
            return expression(e.$arg1()).eq(expression(e.$arg2()));
        else
            throw new IllegalArgumentException("unsupported: " + c);
    }

    private Node node(Table<?> t) {
        if (t instanceof TableAlias<?> ta)
            return node(ta.$aliased()).named(ta.$alias().last());
        else {
            return Cypher.node(nodeName(t));
        }
    }

    private String nodeName(Table<?> t) {

        var comment = t.getComment();
        if (!comment.isBlank()) {
            var config = Arrays.stream(comment.split(",")).map(s -> s.split("="))
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));
            return config.getOrDefault("label", t.getName());
        }

        return t.getName();
    }

    private Node lookupNode(Table<?> t) {
        Node node = tables.get(t);

        if (node != null)
            return node;
        else
            return node(t);
    }
}