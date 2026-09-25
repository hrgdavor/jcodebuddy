// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Custom LanguageClient interface to support custom notifications.
 */
public interface JwaLanguageClient extends LanguageClient {
    @JsonNotification("mytool/jump")
    void jump(JumpParams params);
}
