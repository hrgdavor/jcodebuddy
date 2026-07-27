# reload code without restart

App that uses jetty to handle HTTPS requests (REST or JSONRPC) and also websockets.

- Instead of restarting whole app, separate jetty handling code from rest of the app,
- two main parts: kernel and app
- kernel - contains at least jetty dependencies and handles jetty
- app - runs in separate classloader so it can be reloaded from new source
- interface in kernel code to pass HTTP and websocket to app
- graceful handover
- new version of app can hand over some resources or keep shared resource until completely shutdown
- old version finishes running jobs, and reports when ready to be discarded
- minimal dependencies in kernel or all dependencies in kernel is an option
- full restart needed when dependencies in the kernel need version change
