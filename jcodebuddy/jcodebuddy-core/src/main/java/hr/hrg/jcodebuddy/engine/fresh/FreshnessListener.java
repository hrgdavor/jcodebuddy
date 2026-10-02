package hr.hrg.jcodebuddy.engine.fresh;

/**
 * What a subscriber observes when the sources change (DEC-037 decision 3, plan step 3.0g).
 *
 * <p>The division this interface exists for: <strong>the host watches, the engine says what it means.</strong>
 * DEC-038 settled that the engine takes no watcher — `java-watch*` stays an independent library — so nothing
 * here polls a file or holds a watch service. The host observes a file and calls
 * {@link Freshness#report(hr.hrg.jcodebuddy.engine.index.ClassIndex.ChangeKind, String)}; the engine turns that
 * into the event a consumer can act on. A consumer that wants freshness therefore subscribes here instead of
 * watching files again, which is the duplication this contract removes.</p>
 *
 * @see Freshness
 */
public interface FreshnessListener {

    /** One change, already interpreted: what happened, what it invalidates, and why. */
    void changed(FreshnessEvent event);
}
