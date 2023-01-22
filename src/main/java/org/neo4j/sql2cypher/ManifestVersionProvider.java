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

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import picocli.CommandLine;
import picocli.CommandLine.IVersionProvider;

/**
 * Version provider modelled after the official example. <a href=
 * "https://github.com/remkop/picocli/blob/master/picocli-examples/src/main/java/picocli/examples/VersionProviderDemo2.java">here</a>
 *
 * @author Michael J. Simons
 */
final class ManifestVersionProvider implements IVersionProvider {

	@Override
	public String[] getVersion() throws IOException {
		var resources = CommandLine.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
		while (resources.hasMoreElements()) {
			var url = resources.nextElement();
			var manifest = new Manifest(url.openStream());
			var attributes = manifest.getMainAttributes();
			if ("sql2cypher".equals(get(attributes, "Implementation-Title"))) {
				return new String[] { get(attributes, "Implementation-Version").toString() };
			}
		}
		return new String[0];
	}

	private static Object get(Attributes attributes, String key) {
		return attributes.get(new Attributes.Name(key));
	}

}
