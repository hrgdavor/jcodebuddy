package phase0;

/**
 * Phase 0 scratch file: the target of the stub language server's window/showDocument and
 * workspace/applyEdit requests. Disposable — nothing in the build reads it.
 */
public class Sample {

    public static void main(String[] args) {
        System.out.println("webview phase 0: the stub asked Zed to put the caret on the next line");
        System.out.println("webview phase 0: the stub then asked Zed to apply an edit above this class");
    }
}
