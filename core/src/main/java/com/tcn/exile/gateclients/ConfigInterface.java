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
package com.tcn.exile.gateclients;

import java.util.Date;

public interface ConfigInterface {

    /**
     * Gets the certificate description.
     * 
     * @return The certificate description
     */
    String getCertificateDescription();
    
    /**
     * Gets the certificate name.
     * 
     * @return The certificate name
     */
    String getCertificateName();
    
    /**
     * Gets the API endpoint for this configuration.
     * 
     * @return The API endpoint
     */
    String getApiEndpoint();
    
    /**
     * Gets the SHA-256 fingerprint.
     * 
     * @return The fingerprint
     */
    String getFingerprintSha256();
    
    /**
     * Gets the SHA-256 fingerprint string.
     * 
     * @return The fingerprint string
     */
    String getFingerprintSha256String();
    
    /**
     * Checks if this event represents an unconfigured state.
     * 
     * @return true if unconfigured, false otherwise
     */
    boolean isUnconfigured();
    
    /**
     * Gets the root certificate for this configuration.
     * 
     * @return The root certificate as a string
     */
    String getRootCert();
    
    /**
     * Gets the public certificate for this configuration.
     * 
     * @return The public certificate as a string
     */
    String getPublicCert();
    
    /**
     * Gets the private key for this configuration.
     * 
     * @return The private key as a string
     */
    String getPrivateKey();
    
    /**
     * Gets the expiration date for this configuration.
     * 
     * @return The expiration date
     */
    Date getExpirationDate();
    
    /**
     * Gets the organization/tenant ID this event pertains to.
     * 
     * @return The organization/tenant ID
     */
    String getOrg();
    
    /**
     * Gets the API hostname for this configuration.
     * 
     * @return The API hostname
     * @throws UnconfiguredException if the configuration is not set
     */
    String getApiHostname() throws UnconfiguredException;
    
    /**
     * Gets the API port for this configuration.
     * 
     * @return The API port
     * @throws UnconfiguredException if the configuration is not set
     */
    int getApiPort() throws UnconfiguredException;
}