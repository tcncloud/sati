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
package com.tcn.exile.multitenant;

import java.util.Map;

import com.tcn.exile.gateclients.ConfigInterface;
import com.tcn.exile.gateclients.ConfigEventInterface;
/**
 * Interface for tenant-specific beans.
 * Each tenant instance represents a separate tenant in the multi-tenant system.
 */
public interface Tenant {
    
    /**
     * Run method that will be called periodically for this tenant.
     * Implementations should perform tenant-specific periodic operations.
     */
    void run();
 
    /**
     * Gets the tenant ID.
     * 
     * @return The tenant identifier
     */
    String getTenantId();
    
    /**
     * Gets the tenant configuration.
     * 
     * @return The tenant configuration data
     */
    ConfigInterface getConfig();

    void onConfigEvent(ConfigEventInterface event);

    

} 