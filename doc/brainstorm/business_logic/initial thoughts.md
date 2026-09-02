# Java business logic via creating outcomes

Create outcome actions as separate steps so they can be done in bulk, or combined, and also easier to test without mocking calls to service like sending email, when such call has to produce all the data for doing so anyway. can increase testability and reviewability of a process. This sounds like data oriented a bit.

Debug mode where additional optional(tied to log level or some other way to turn on/off) snapshot of changes are produces to see what step changed what if for example multiple steps affect same data object. Also attach stack trace to debug info with data change. Maybe also inject into debug metadata log output during execution somehow catch log statements, or use a specialized log writer that can pass to debug collector too. Logger should be param to method doing business logic maybe.

Try to make code that does the logic look more like regular code instead spread all over like event based stuff. 

I want to control identifiers inside code not rely on database to generate, so entity update containers must have additional marker that says if it is update or add.

Look for optimizing memory usage in these cases, and performance.

this can allow for WAL, where app sends data to database async and app caches are already in line with what DB will be in short time.

special handling of arrays on entity top level by forcing change marker or specialized impl that propagates marker down to owner.

Define UpdatePair<Immutable, Update> that can be dependency for a step. Code generator makes sure combines steps read as regular code.