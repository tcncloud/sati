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

package com.tcn.exile.config;

import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;

@Singleton
public class ConfigChangeEventListener  implements ApplicationEventListener<ConfigEvent>{

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ConfigChangeEventListener.class);

    @Override
    public void onApplicationEvent(ConfigEvent event) {
        log.info("Config change event: {}", event);
        // throw new UnsupportedOperationException("Unimplemented method 'onApplicationEvent'");
    }
    // @EventListener
    // public void onConfigChange(ConfigChangeEvent changeEvent) {
    //     log.info("Config change event: {}", changeEvent);
    // }
}