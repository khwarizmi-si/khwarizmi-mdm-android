/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single application present on device, reported as part of the full installed-app
 * inventory (every package, including user-installed and system apps). This is distinct
 * from {@link Application}, which only describes the apps managed by the MDM configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstalledApp {

    private String pkg;
    private String name;
    private String version;
    private Long versionCode;
    private boolean system;
    private boolean launchable;
    private String installer;
    private Long firstInstall;
    private Long lastUpdate;

    public InstalledApp() {
    }

    public String getPkg() {
        return pkg;
    }

    public void setPkg(String pkg) {
        this.pkg = pkg;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Long getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(Long versionCode) {
        this.versionCode = versionCode;
    }

    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }

    public boolean isLaunchable() {
        return launchable;
    }

    public void setLaunchable(boolean launchable) {
        this.launchable = launchable;
    }

    public String getInstaller() {
        return installer;
    }

    public void setInstaller(String installer) {
        this.installer = installer;
    }

    public Long getFirstInstall() {
        return firstInstall;
    }

    public void setFirstInstall(Long firstInstall) {
        this.firstInstall = firstInstall;
    }

    public Long getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(Long lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
