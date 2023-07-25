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

import java.util.Map;
import java.util.Objects;

import org.jooq.SQLDialect;
import org.jooq.conf.ParseNameCase;
import org.jooq.conf.RenderNameCase;

/**
 * Configuration for the {@link Translator}, use this to configure parsing and rendering
 * settings as well as table to node mappings.
 *
 * @author Michael Hunger
 * @author Michael J. Simons
 */
public final class TranslatorConfig {

	private static final TranslatorConfig DEFAULT_CONFIG = TranslatorConfig.builder().build();

	/**
	 * A builder for creating new {@link TranslatorConfig configuration objects}.
	 * @return a new builder for creating a new configuration from scratch.
	 */
	public static TranslatorConfig.Builder builder() {
		return new Builder();
	}

	/**
	 * Provides access to the default configuration.
	 * @return the default configuration ready to use.
	 */
	public static TranslatorConfig defaultConfig() {

		return DEFAULT_CONFIG;
	}

	private final ParseNameCase parseNameCase;

	private final RenderNameCase renderNameCase;

	private final boolean jooqDiagnosticLogging;

	private final Map<String, String> tableToLabelMappings;

	private final Map<String, String> joinColumnsToTypeMappings;

	private final SQLDialect sqlDialect;

	private final boolean prettyPrint;

	private final String parseNamedParamPrefix;

	private TranslatorConfig(Builder builder) {

		this.parseNameCase = builder.parseNameCase;
		this.renderNameCase = builder.renderNameCase;
		this.jooqDiagnosticLogging = builder.jooqDiagnosticLogging;
		this.tableToLabelMappings = builder.tableToLabelMappings;
		this.joinColumnsToTypeMappings = builder.joinColumnsToTypeMappings;
		this.sqlDialect = builder.sqlDialect;
		this.prettyPrint = builder.prettyPrint;
		this.parseNamedParamPrefix = builder.parseNamedParamPrefix;
	}

	/**
	 * Allows modifying this configuration.
	 * @return builder with all settings from this instance
	 */
	public Builder modify() {
		return new Builder(this);
	}

	public ParseNameCase getParseNameCase() {
		return this.parseNameCase;
	}

	public RenderNameCase getRenderNameCase() {
		return this.renderNameCase;
	}

	public boolean isJooqDiagnosticLogging() {
		return this.jooqDiagnosticLogging;
	}

	public Map<String, String> getTableToLabelMappings() {
		return this.tableToLabelMappings;
	}

	public Map<String, String> getJoinColumnsToTypeMappings() {
		return this.joinColumnsToTypeMappings;
	}

	public SQLDialect getSqlDialect() {
		return this.sqlDialect;
	}

	public boolean isPrettyPrint() {
		return this.prettyPrint;
	}

	public String getParseNamedParamPrefix() {
		return this.parseNamedParamPrefix;
	}

	/**
	 * A builder to create new instances of {@link TranslatorConfig configurations}.
	 */
	public static final class Builder {

		private ParseNameCase parseNameCase;

		private RenderNameCase renderNameCase;

		private boolean jooqDiagnosticLogging;

		private Map<String, String> tableToLabelMappings;

		private Map<String, String> joinColumnsToTypeMappings;

		private SQLDialect sqlDialect;

		private boolean prettyPrint;

		private String parseNamedParamPrefix;

		private Builder() {
			this(ParseNameCase.LOWER_IF_UNQUOTED, RenderNameCase.LOWER, false, Map.of(), Map.of(), SQLDialect.DEFAULT,
					true, null);
		}

		private Builder(TranslatorConfig config) {
			this(config.parseNameCase, config.renderNameCase, config.jooqDiagnosticLogging, config.tableToLabelMappings,
					config.joinColumnsToTypeMappings, config.sqlDialect, config.prettyPrint,
					config.parseNamedParamPrefix);
		}

		private Builder(ParseNameCase parseNameCase, RenderNameCase renderNameCase, boolean jooqDiagnosticLogging,
				Map<String, String> tableToLabelMappings, Map<String, String> joinColumnsToTypeMappings,
				SQLDialect sqlDialect, boolean prettyPrint, String parseNamedParamPrefix) {
			this.parseNameCase = parseNameCase;
			this.renderNameCase = renderNameCase;
			this.jooqDiagnosticLogging = jooqDiagnosticLogging;
			this.tableToLabelMappings = tableToLabelMappings;
			this.joinColumnsToTypeMappings = joinColumnsToTypeMappings;
			this.sqlDialect = sqlDialect;
			this.prettyPrint = prettyPrint;
			this.parseNamedParamPrefix = parseNamedParamPrefix;
		}

		/**
		 * Configures how names should be parsed.
		 * @param newParseNameCase the new configuration
		 * @return this builder
		 */
		public Builder withParseNameCase(ParseNameCase newParseNameCase) {
			this.parseNameCase = Objects.requireNonNull(newParseNameCase);
			return this;
		}

		/**
		 * Configures how SQL names should be parsed.
		 * @param newRenderNameCase the new configuration
		 * @return this builder
		 */
		public Builder withRenderNameCase(RenderNameCase newRenderNameCase) {
			this.renderNameCase = Objects.requireNonNull(newRenderNameCase);
			return this;
		}

		/**
		 * Enables diagnostic logging for jOOQ.
		 * @param enabled set to {@literal true} to enable diagnostic logging on the jOOQ
		 * side of things
		 * @return this builder
		 */
		public Builder withJooqDiagnosticLogging(boolean enabled) {
			this.jooqDiagnosticLogging = enabled;
			return this;
		}

		/**
		 * Applies new table mappings.
		 * @param newTableToLabelMappings the new mappings
		 * @return this builder
		 */
		public Builder withTableToLabelMappings(Map<String, String> newTableToLabelMappings) {
			this.tableToLabelMappings = Map.copyOf(Objects.requireNonNull(newTableToLabelMappings));
			return this;
		}

		/**
		 * Applies new join column mappings.
		 * @param newJoinColumnsToTypeMappings the new mappings
		 * @return this builder
		 */
		public Builder withJoinColumnsToTypeMappings(Map<String, String> newJoinColumnsToTypeMappings) {
			this.joinColumnsToTypeMappings = Map.copyOf(Objects.requireNonNull(newJoinColumnsToTypeMappings));
			return this;
		}

		/**
		 * Applies a new {@link SQLDialect} for both parsing and optionally rendering SQL.
		 * @param newSqlDialect the new sql dialect
		 * @return this builder
		 */
		public Builder withSqlDialect(SQLDialect newSqlDialect) {
			this.sqlDialect = Objects.requireNonNull(newSqlDialect);
			return this;
		}

		/**
		 * Enables or disables pretty printing of the generated Cypher queries.
		 * @param prettyPrint set to {@literal false} to disable pretty printing
		 * @return this builder
		 */
		public Builder withPrettyPrint(boolean prettyPrint) {
			this.prettyPrint = prettyPrint;
			return this;
		}

		/**
		 * Changes the prefix used for parsing named parameters. If set to
		 * {@literal null}, the jOOQ default ({@literal :}) is used.
		 * @param parseNamedParamPrefix the new prefix for parsing named parameters
		 * @return this builder
		 */
		public Builder withParseNamedParamPrefix(String parseNamedParamPrefix) {
			this.parseNamedParamPrefix = parseNamedParamPrefix;
			return this;
		}

		/**
		 * Finishes building a new configuration. The builder is safe to reuse afterwards.
		 * @return a new immutable configuration
		 */
		public TranslatorConfig build() {
			return new TranslatorConfig(this);
		}

	}

}
