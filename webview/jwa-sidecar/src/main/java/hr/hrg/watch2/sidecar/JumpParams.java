// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

public class JumpParams {
    public String uri;
    public int line;
    public int column;

    public JumpParams() {
    }

    public JumpParams(String uri, int line, int column) {
        this.uri = uri;
        this.line = line;
        this.column = column;
    }
}
