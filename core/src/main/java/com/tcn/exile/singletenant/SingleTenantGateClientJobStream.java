/* 
 *  Copyright 2017-2024 original authors
 *  
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *  https://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.tcn.exile.singletenant;

import com.tcn.exile.gateclients.v2.GateClientJobStream;
import com.tcn.exile.plugin.PluginInterface;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.scheduling.annotation.Scheduled;
import tcnapi.exile.gate.v2.Public.StreamJobsResponse;

@Singleton
@Requires(property = "sati.tenant.type", value = "single")
public class SingleTenantGateClientJobStream extends GateClientJobStream {
    protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SingleTenantGateClientJobStream.class);

    @Inject
    PluginInterface plugin;

    // @Override
    // @Scheduled(fixedDelay = "1s")
    // public void start() {
    //     super.start();
    // }

    @Override
    public void onNext(StreamJobsResponse value) {
        super.onNext(value);
    }

    @Override
    public void onError(Throwable t) {
        super.onError(t);
    }

    @Override
    public void onCompleted() {
        super.onCompleted();
    }
    // @Override
    // public void onApplicationEvent(ConfigEventInterface event) {
    //     super.onApplicationEvent(event);
    //     log.info("Received ConfigEventInterface {}", event);
    // }
} 