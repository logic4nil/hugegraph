/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.store.hstore;

import com.baidu.hugegraph.config.ConfigOption;
import com.baidu.hugegraph.config.OptionHolder;

import static com.baidu.hugegraph.config.OptionChecker.disallowEmpty;

public class HstoreOptions extends OptionHolder {

    private HstoreOptions() {
        super();
    }

    private static volatile HstoreOptions instance;

    public static synchronized HstoreOptions instance() {
        if (instance == null) {
            instance = new HstoreOptions();
            instance.registerOptions();
        }
        return instance;
    }

    public static final ConfigOption<String> PD_PEERS = new ConfigOption<>(
            "pd.peers", "The addresses of store nodes, separated with commas.",
            disallowEmpty(), "localhost");
}