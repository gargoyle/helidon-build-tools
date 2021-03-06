/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.build.cli.impl;

import java.io.IOException;
import java.io.UncheckedIOException;

import io.helidon.build.cli.impl.TestMetadata.TestVersion;
import io.helidon.build.test.TestFiles;
import io.helidon.build.util.UserConfig;

/**
 * Base class for command tests that require the {@link Metadata}.
 */
public class MetadataCommandTest extends BaseCommandTest {

    private MetadataTestServer server;
    private Metadata metadata;

    public void startMetadataAccess(boolean verbose, boolean clearCache) {
        Config.setUserHome(TestFiles.targetDir().resolve("alice"));
        if (clearCache) {
            final UserConfig userConfig = Config.userConfig();
            try {
                userConfig.clearCache();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        server = new MetadataTestServer(TestVersion.RC1, verbose).start();
        metadata = Metadata.newInstance(server.url());
    }

    public String metadataUrl() {
        return server.url();
    }

    public Metadata metadata() {
        return metadata;
    }

    public void stopMetadataAccess() {
        if (server != null) {
            server.stop();
        }
    }
}
