package hr.hrg.jetbrains.webview.services;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RememberedHostUrlTest {

    @Test
    public void movesThePortAndTokenOfThisHostsAppUrl() {
        assertEquals("http://127.0.0.1:18887/app/?openFile=README.md&token=new",
                RememberedHostUrl.rewrite(
                        "http://127.0.0.1:18886/app/?openFile=README.md&token=old",
                        18886, 18887, "new"));
    }

    @Test
    public void keepsAnEncodedOpenFilePathIntact() {
        assertEquals("http://127.0.0.1:18887/app/?openFile=src%2FA.java&token=new",
                RememberedHostUrl.rewrite(
                        "http://127.0.0.1:18886/app/?openFile=src%2FA.java&token=old",
                        18886, 18887, "new"));
    }

    @Test
    public void leavesAnotherHostsUrlAlone() {
        String custom = "http://127.0.0.1:19000/app/?openFile=README.md";
        assertEquals(custom, RememberedHostUrl.rewrite(custom, 18886, 18887, "new"));
    }

    @Test
    public void leavesFileAndNonAppUrlsAlone() {
        assertEquals("file:///D:/work/README.md",
                RememberedHostUrl.rewrite("file:///D:/work/README.md", 18886, 18887, "new"));
        assertEquals("http://127.0.0.1:18886/health",
                RememberedHostUrl.rewrite("http://127.0.0.1:18886/health", 18886, 18887, "new"));
    }

    @Test
    public void refreshesTheTokenWhenThePortDidNotMove() {
        assertEquals("http://127.0.0.1:18886/app/?openFile=README.md&token=new",
                RememberedHostUrl.rewrite(
                        "http://127.0.0.1:18886/app/?openFile=README.md&token=old",
                        18886, 18886, "new"));
    }
}
