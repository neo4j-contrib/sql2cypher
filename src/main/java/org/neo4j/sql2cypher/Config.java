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

import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DefaultConfiguration;
import org.neo4j.cypherdsl.core.renderer.Configuration;

/**
 * Configuration for sql2cypher, use this to configure parsing and rendering settings as
 * well as table to node mappings.
 *
 * @author Michael Hunger
 * @author Michael J. Simons
 */
public final class Config {

	private final Settings jooqSettings;

	private final Map<String, String> tableToLabelMappings;

	private final SQLDialect sqlDialect;

	private final Configuration cypherDslConfig;

	public Config() {
		this(Map.of(), defaultSettings(), SQLDialect.DEFAULT, Configuration.prettyPrinting());
	}

	public Config(Map<String, String> tableToLabelMappings, Settings jooqSettings, SQLDialect sqlDialect,
			Configuration cypherDslConfig) {
		this.tableToLabelMappings = Map.copyOf(tableToLabelMappings);
		this.sqlDialect = sqlDialect;
		this.jooqSettings = ((Settings) jooqSettings.clone()).withParseDialect(this.sqlDialect);
		this.cypherDslConfig = cypherDslConfig;
	}

	public Config with(Map<String, String> tableMappings) {
		return new Config(tableMappings, this.jooqSettings, this.sqlDialect, this.cypherDslConfig);
	}

	private static Settings defaultSettings() {
		return new DefaultConfiguration().settings().withParseNameCase(org.jooq.conf.ParseNameCase.LOWER_IF_UNQUOTED)
				.withRenderNameCase(org.jooq.conf.RenderNameCase.LOWER)
				.withParseWithMetaLookups(org.jooq.conf.ParseWithMetaLookups.IGNORE_ON_FAILURE)
				.withDiagnosticsLogging(true);
	}

	public Settings getJooqSettings() {
		return ((Settings) this.jooqSettings.clone());
	}

	public Map<String, String> getTableToLabelMappings() {
		return Map.copyOf(this.tableToLabelMappings);
	}

	public SQLDialect getSqlDialect() {
		return this.sqlDialect;
	}

	public Configuration getCypherDslConfig() {
		return this.cypherDslConfig;
	}

}
