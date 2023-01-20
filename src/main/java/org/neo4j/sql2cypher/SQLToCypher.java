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
import java.util.Map;

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

import static org.jooq.impl.DSL.createTable;

/**
 * Quick proof of concept of a jOOQ/Cypher-DSL based SQL to Cypher translator
 * @author Lukas Eder
 * @author Michael J. Simons
 */
public class SQLToCypher {

    public static void main(String[] args) {
        System.out.println(new SQLToCypher().convert("""
            SELECT t.a, t.b
            FROM my_table AS t
            WHERE t.a = 1
            """));
    }

    // Meta data can be added via .meta(createTable("t").column("a", SQLDataType.INTEGER))
    private final Parser parser = DSL.using(SQLDialect.DEFAULT).parser();
    private final Renderer renderer = Renderer.getRenderer(Configuration.prettyPrinting());
    private final Map<Table<?>, Node> tables = new HashMap<>();

    // Unsure how thread safe this should be (wrt the node lookup table), but this here will do for the purpose of adding some tests
    public String convert(String sql) {
        Select<?> query = parser.parseSelect(sql);
        return renderer.render(statement(query));
    }

    private ResultStatement statement(Select<?> x) {
        OngoingReadingWithoutWhere m1 = Cypher
            .match(x.$from().stream().map(t -> {
                Node node = node(t);
                tables.put(t, node);
                return (PatternElement) node;
            }).toList());

        OngoingReadingWithWhere m2 = x.$where() != null
             ? m1.where(condition(x.$where()))
             : (OngoingReadingWithWhere) m1;

        return m2.returning(x.$select().stream().map(t -> (Expression) expression(t)).toList()).build();
    }

    private Expression expression(SelectFieldOrAsterisk t) {
        if (t instanceof SelectField<?> s)
            return expression(s);
        else
            throw new IllegalArgumentException("unsupported: " + t);
    }

    private Expression expression(SelectField<?> s) {
        if (s instanceof QOM.FieldAlias<?> fa && fa.$alias() != null)
            return expression(fa.$aliased()).as(fa.$alias().last());
        else if (s instanceof Field<?> f)
            return expression(f);
        else
            throw new IllegalArgumentException("unsupported: " + s);
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
        if (t instanceof TableAlias<?> ta && ta.$alias() != null)
            return node(ta.$aliased()).named(ta.$alias().last());
        else
            return Cypher.node(t.getName());
    }

    private Node lookupNode(Table<?> t) {
        Node node = tables.get(t);

        if (node != null)
            return node;
        else
            return node(t);
    }

    static final <T> T println(T t) {
        System.out.println(t);
        return t;
    }
}