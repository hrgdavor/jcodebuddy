// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.IOException;

/**
 * Manages the list of pending actions for both TUI and Web UI.
 */
public class PendingActionManager {
    private final List<PendingAction> actions = Collections.synchronizedList(new ArrayList<>());

    public void addAction(PendingAction action) {
        if (action != null) {
            actions.add(action);
        }
    }

    public List<PendingAction> getActions() {
        return new ArrayList<>(actions);
    }

    public void removeAction(int index) {
        if (index >= 0 && index < actions.size()) {
            actions.remove(index);
        }
    }

    public void accept(int index) throws IOException {
        if (index >= 0 && index < actions.size()) {
            PendingAction action = actions.remove(index);
            action.accept();
        }
    }

    public void reject(int index) throws IOException {
        if (index >= 0 && index < actions.size()) {
            PendingAction action = actions.remove(index);
            action.reject();
        }
    }

    public PendingAction getAction(int index) {
        if (index >= 0 && index < actions.size()) {
            return actions.get(index);
        }
        return null;
    }
}
