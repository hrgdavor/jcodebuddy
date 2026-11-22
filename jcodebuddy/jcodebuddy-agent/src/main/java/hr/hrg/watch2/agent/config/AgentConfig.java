// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Configuration for the agent's web host and tool process. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {
    private boolean webEnabled = true;
    private int webPort = 6666;
    private String webUser = "jcodebuddy_agent";
    private String webPassword;

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public void setWebEnabled(boolean webEnabled) {
        this.webEnabled = webEnabled;
    }

    public int getWebPort() {
        return webPort;
    }

    public void setWebPort(int webPort) {
        this.webPort = webPort;
    }

    public String getWebUser() {
        return webUser;
    }

    public void setWebUser(String webUser) {
        this.webUser = webUser;
    }

    public String getWebPassword() {
        return webPassword;
    }

    public void setWebPassword(String webPassword) {
        this.webPassword = webPassword;
    }
}
