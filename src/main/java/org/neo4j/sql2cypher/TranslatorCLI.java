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

import org.jooq.SQLDialect;
import org.jooq.conf.ParseNameCase;
import picocli.AutoComplete.GenerateCompletion;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Main entry to the {@link Translator translator cli}.
 *
 * @author Michael J. Simons
 */
@SuppressWarnings({"FieldMayBeFinal"})
@Command(name = "sql2cypher", mixinStandardHelpOptions = true,
		description = "Translates SQL statements to Cypher queries.", sortOptions = false,
		versionProvider = ManifestVersionProvider.class, subcommands = { GenerateCompletion.class, HelpCommand.class })
public final class TranslatorCLI implements Runnable {

	@Option(names = "--parse-name-case",
			description = "How to parse names; valid values are: ${COMPLETION-CANDIDATES} and the default is ${DEFAULT-VALUE}")
	private ParseNameCase parseNameCase = TranslatorConfig.defaultConfig().getParseNameCase();

	@Option(names = "--table-to-label-mapping",
			description = "A table name that should be mapped to a specific label, repeat for multiple mappings")
	private Map<String, String> tableToLabelMappings = new HashMap<>();

	@Option(names = "--sql-dialect",
			description = "The SQL dialect to use for parsing; valid values are: ${COMPLETION-CANDIDATES} and the default is ${DEFAULT-VALUE}")
	private SQLDialect sqlDialect = TranslatorConfig.defaultConfig().getSqlDialect();

	@Option(names = "--disable-pretty-printing", description = "Disables pretty printing")
	private boolean disablePrettyPrinting = false;

	@Parameters(index = "0", description = "Any valid SQL statement that should be translated to Cypher")
	private String sql;

	public static void main(String... args) {

		var commandLine = new CommandLine(new TranslatorCLI()).setCaseInsensitiveEnumValuesAllowed(true);

		var generateCompletionCmd = commandLine.getSubcommands().get("generate-completion");
		generateCompletionCmd.getCommandSpec().usageMessage().hidden(true);

		int exitCode = commandLine.execute(args);
		System.exit(exitCode);
	}

	@Override
	public void run() {

		var cfg = TranslatorConfig.builder().withParseNameCase(this.parseNameCase)
				.withTableToLabelMappings(this.tableToLabelMappings).withSqlDialect(this.sqlDialect)
				.withPrettyPrint(!this.disablePrettyPrinting).build();
		System.out.println(Translator.with(cfg).convert(this.sql));
	}

}
