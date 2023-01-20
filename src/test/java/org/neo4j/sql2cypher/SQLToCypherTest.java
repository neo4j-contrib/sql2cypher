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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.ast.Block;
import org.asciidoctor.ast.ContentNode;
import org.asciidoctor.ast.Document;
import org.asciidoctor.extension.Treeprocessor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.bytebuddy.asm.Advice;

/**
 * @author Michael J. Simons
 */
class SQLToCypherTest {

	static List<TestData> getTestData(String resourceName) throws Exception {
		try (var asciidoctor = Asciidoctor.Factory.create()) {
			var collector = new TestDataExtractor();
			asciidoctor.javaExtensionRegistry().treeprocessor(collector);

			var content = Files.readString(Paths.get(Objects.requireNonNull(SQLToCypherTest.class.getResource(resourceName)).toURI()));
			asciidoctor.load(content, Options.builder().build());
			return collector.testData;
		}
	}

	static Stream<Arguments> simple() throws Exception {
		return getTestData("/simple.adoc").stream()
			.map(t -> Arguments.of(t.name(), t.sql(), t.cypher()));

	}

	@ParameterizedTest(name = "{0}")
	@MethodSource
	void simple(String name, String sql, String expected) {
		assertThat(new SQLToCypher().convert(sql)).isEqualTo(expected);
	}

	private static class TestDataExtractor extends Treeprocessor {

		private final List<TestData> testData = new ArrayList<>();

		TestDataExtractor() {
			super(new HashMap<>()); // Must be mutable
		}

		@Override
		public Document process(Document document) {

			var blocks = document
				.findBy(Map.of("context", ":listing", "style", "source"))
				.stream()
				.map(Block.class::cast)
				.filter(b -> b.hasAttribute("id"))
				.collect(Collectors.toMap(ContentNode::getId, Function.identity()));

			blocks.values().stream()
				.filter(b -> "sql".equals(b.getAttribute("language")))
				.map(sqlBlock -> {
					var name = (String) sqlBlock.getAttribute("name");
					var sql = String.join(System.lineSeparator(), sqlBlock.getLines());
					var cypher = String.join(System.lineSeparator(), blocks.get(sqlBlock.getId() + "_expected").getLines());
					return new TestData(name, sql, cypher);
				})
				.forEach(testData::add);
			return document;
		}
	}

	private record TestData(String name, String sql, String cypher) {
	}
}
