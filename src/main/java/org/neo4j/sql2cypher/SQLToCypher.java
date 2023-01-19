/*
 * TODO: Setup headers
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

import org.jooq.impl.SQLDataType;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.cypherdsl.core.Expression;
import org.neo4j.cypherdsl.core.Node;
import org.neo4j.cypherdsl.core.PatternElement;
import org.neo4j.cypherdsl.core.ResultStatement;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithWhere;
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReadingWithoutWhere;
import org.neo4j.cypherdsl.core.renderer.Renderer;

import static org.jooq.impl.DSL.createTable;

/**
 * Quick proof of concept of a jOOQ/Cypher-DSL based SQL to Cypher translator
 * @author Lukas Eder
 */
public class SQLToCypher {

    public static void main(String[] args) {
        println(DSL.using(SQLDialect.DEFAULT).meta(createTable("t").column("a", SQLDataType.INTEGER)));
        Parser parser = DSL.using(SQLDialect.DEFAULT).parser();

        Select<?> x = parser.parseSelect("""
            SELECT t.a, t.b
            FROM my_table AS t
            WHERE t.a = 1
            """);

        println(x);
        println(Renderer.getDefaultRenderer().render(new SQLToCypher().statement(x)));
    }

    Map<Table<?>, Node> tables = new HashMap<>();

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