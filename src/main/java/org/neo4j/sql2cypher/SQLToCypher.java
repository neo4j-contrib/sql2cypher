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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jooq.Field;
import org.jooq.Param;
import org.jooq.Parser;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.SelectField;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.jooq.impl.QOM.TableAlias;

import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Expression;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.PatternElement;
import org.neo4j.cypherdsl.core.ResultStatement;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithWhere;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithoutWhere;
import org.neo4j.cypherdsl.core.renderer.Configuration;
import org.neo4j.cypherdsl.core.renderer.Renderer;

/**
 * Quick proof of concept of a jOOQ/Cypher-DSL based SQL to Cypher translator
 * @author Lukas Eder
 * @author Michael J. Simons
 */
public class SQLToCypher {


    public static void main(String[] args) {
        System.out.println(new SQLToCypher().using(new Config()).convert("""
            SELECT t.a, t.b
            FROM my_table AS t
            WHERE t.a = 1
            """));
    }

    private Config config = new Config();

    static class Context {
        private final Map<Table<?>, Node> tables = new HashMap<>();

        void addTable(Table<?> table, Node node) {
            this.tables.put(table,node);
        }

        private Node getNode(Table<?> t) {
            return tables.get(t);
        }
    }

    static class Config {
        private final SQLDialect dialect;
        private final Configuration cypherDslConfig;

        public Config() {
            this.dialect = SQLDialect.DEFAULT;
            this.cypherDslConfig = Configuration.prettyPrinting();
        }

        public Config(SQLDialect dialect, Configuration cypherDslConfig) {
            this.dialect = dialect;
            this.cypherDslConfig = cypherDslConfig;
        }

        public SQLDialect getDialect() {
            return dialect;
        }

        public Configuration getCypherDslConfig() {
            return cypherDslConfig;
        }
    }
    public SQLToCypher using(Config config) {
        this.config = config;
        return this;
    }

    // Unsure how thread safe this should be (wrt the node lookup table), but this here will do for the purpose of adding some tests
    public String convert(String sql) {
        Context ctx = new Context();
        Select<?> query = parse(sql);
        ResultStatement statement = statement(query, ctx);
        return render(statement);
    }

    Select<?> parse(String sql) {
        Parser parser = DSL.using(config.getDialect()).parser();
        return parser.parseSelect(sql);
    }

    String render(ResultStatement statement) {
        var renderer = Renderer.getRenderer(config.getCypherDslConfig());
        return renderer.render(statement);
    }

    ResultStatement statement(Select<?> x, Context ctx) {

        // Done lazy as otherwise the property containers won't be resolved
        Supplier<List<Expression>> resultColumnsSupplier =
                () -> x.$select().stream()
                        .map(t -> (Expression) expression(t, ctx)).toList();

        if (x.$from().isEmpty()) {
            return Cypher.returning(resultColumnsSupplier.get()).build();
        }

        OngoingReadingWithoutWhere m1 = Cypher
            .match(x.$from().stream().map(t -> {
                Node node = node(t);
                ctx.addTable(t, node);
                return (PatternElement) node;
            }).toList());

        OngoingReadingWithWhere m2 = x.$where() != null
             ? m1.where(condition(x.$where(), ctx))
             : (OngoingReadingWithWhere) m1;

        return m2.returning(resultColumnsSupplier.get()).build();
    }

    private Expression expression(SelectFieldOrAsterisk t, Context ctx) {
        if (t instanceof SelectField<?> s)
            return expression(s, ctx);
        else
            throw new IllegalArgumentException("unsupported: " + t);
    }

    private Expression expression(SelectField<?> s, Context ctx) {
        if (s instanceof QOM.FieldAlias<?> fa)
            return expression(fa.$aliased(), ctx).as(fa.$alias().last());
        else if (s instanceof Field<?> f)
            return expression(f, ctx);
        else
            throw new IllegalArgumentException("unsupported: " + s);
    }

    private Expression expression(Field<?> f, Context ctx) {
        if (f instanceof Param<?> p)
            if (p.$inline())
                return Cypher.literalOf(p.getValue());
            else if (p.getParamName() != null)
                return Cypher.parameter(p.getParamName(), p.getValue());
            else
                return Cypher.anonParameter(p.getValue());
        else if (f instanceof TableField<?, ?> tf) {
            Table<?> t = tf.getTable();
            return lookupNode(t, ctx).property(tf.getName());
        }
        else
            throw new IllegalArgumentException("unsupported: " + f);
    }

    private Node lookupNode(Table<?> t, Context ctx) {
        Node node = ctx.getNode(t);
        if (node != null)
            return node;
        else
            return node(t);
    }

    private Condition condition(org.jooq.Condition c, Context ctx) {
        if (c instanceof QOM.And a)
            return condition(a.$arg1(), ctx).and(condition(a.$arg2(), ctx));
        else if (c instanceof QOM.Or o)
            return condition(o.$arg1(), ctx).or(condition(o.$arg2(), ctx));
        else if (c instanceof QOM.Eq<?> e)
            return expression(e.$arg1(), ctx).eq(expression(e.$arg2(), ctx));
        else
            throw new IllegalArgumentException("unsupported: " + c);
    }

    private Node node(Table<?> t) {
        if (t instanceof TableAlias<?> ta)
            return node(ta.$aliased()).named(ta.$alias().last());
        else
            return Cypher.node(t.getName());
    }
}