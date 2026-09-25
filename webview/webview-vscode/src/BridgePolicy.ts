/**
 * The authorization, CORS and rate-limit decisions of the VS Code host, as pure functions.
 *
 * Extracted from `HttpBridge` so it can be tested without VS Code, and so that the exact rules that were
 * wrong in this host are the rules under test.
 *
 * What was wrong here before, and why this file exists:
 *
 *  - `/open` tested the allow-list only when the request carried an `Origin` header
 *    (`if (origin && !this.isOriginAllowed(origin))`). A request with **no** `Origin` — which is what a
 *    `file://` page, a hidden iframe, and any non-browser client send — skipped the check entirely and was
 *    served. The rule is now the same as `webview-core`'s `AllowedOrigins`: an empty allow-list denies
 *    everyone, and an absent `Origin` is a denial rather than a skip.
 *  - `/file/` answered `Access-Control-Allow-Origin: *` to every caller. It now sends a grant only to an
 *    origin the allow-list names.
 *
 * Every decision here is asserted against `../../conformance/bridge-decisions.json`, which the Java host
 * reads as well, so the hosts cannot drift apart quietly again.
 */

/** The authorization and CORS configuration a bridge runs with. */
export interface BridgeConfig {
    /** Comma-separated origins, or '' for none. An empty list denies every caller. */
    allowedOrigins: string;
    /** Optional shared secret; when set, presenting it authorizes a caller with no usable Origin. */
    token?: string;
}

/** Normalises one origin: trimmed, lower-cased, and with a trailing slash removed. */
export function normalizeOrigin(origin: string): string {
    let value = origin.trim().toLowerCase();
    while (value.length > 1 && value.endsWith('/') && !value.endsWith('://')) {
        value = value.slice(0, -1);
    }
    return value;
}

/** The configured origins, normalised. */
export function parseAllowedOrigins(commaSeparated: string | undefined): string[] {
    if (!commaSeparated) {
        return [];
    }
    return commaSeparated
        .split(',')
        .map(part => part.trim())
        .filter(part => part.length > 0)
        .map(normalizeOrigin);
}

/**
 * True when `origin` is explicitly allowed. An empty list denies everything, and a missing or blank
 * origin is a denial: this is the case that used to be skipped.
 */
export function allowsOrigin(commaSeparated: string | undefined, origin: string | null | undefined): boolean {
    if (origin === null || origin === undefined || origin.trim().length === 0) {
        return false;
    }
    const allowed = parseAllowedOrigins(commaSeparated);
    if (allowed.length === 0) {
        return false;
    }
    return allowed.includes(normalizeOrigin(origin));
}

/**
 * Whether a grant may be sent at all. A caller that is not allowed gets no CORS headers, so a hostile page
 * cannot even read the response's status.
 *
 * The value echoed is always the caller's own origin, never `*` and never a configured constant: `*` would
 * grant every page in the browser the right to read the answer.
 */
export function corsGrant(commaSeparated: string | undefined, origin: string | null | undefined): string | null {
    return allowsOrigin(commaSeparated, origin) ? (origin as string) : null;
}

/**
 * Whether a request may act. A caller is authorized by presenting the token, or by an allowed `Origin`.
 * With neither configured, nothing is authorized — the closed default.
 */
export function isAuthorized(
    config: BridgeConfig,
    origin: string | null | undefined,
    suppliedToken: string | null | undefined
): boolean {
    const token = (config.token ?? '').trim();
    if (token.length > 0 && suppliedToken === token) {
        return true;
    }
    return allowsOrigin(config.allowedOrigins, origin);
}

/** A sliding-window rate limiter: at most `limit` events per `windowMillis`. */
export class RateLimiter {
    private timestamps: number[] = [];

    constructor(
        private readonly limit: number,
        private readonly windowMillis: number,
        private readonly now: () => number = () => Date.now()
    ) {
        if (limit < 1) {
            throw new Error(`limit must be >= 1, was ${limit}`);
        }
        if (windowMillis < 1) {
            throw new Error(`windowMillis must be >= 1, was ${windowMillis}`);
        }
    }

    /**
     * Records one event and reports whether it is allowed. A refused event is **not** recorded, so a burst
     * of refused calls cannot keep the window full — the same rule as the Java hosts.
     */
    tryAcquire(): boolean {
        const now = this.now();
        this.timestamps = this.timestamps.filter(timestamp => now - timestamp <= this.windowMillis);
        if (this.timestamps.length >= this.limit) {
            return false;
        }
        this.timestamps.push(now);
        return true;
    }

    windowSize(): number {
        return this.timestamps.length;
    }
}

/** The production policy, matching `Navigator.RATE_LIMIT_COUNT` / `RATE_LIMIT_WINDOW_MS` in webview-core. */
export const PRODUCTION_RATE_LIMIT = { count: 20, windowMillis: 20_000 };

/** The name a page sees for this host, matching `HostHealth.PLUGIN_VSCODE` in webview-core. */
export const PLUGIN_VSCODE = 'vscode-webview-explorer';

/** The bridge protocol version, matching `InjectedBridge.VERSION` in webview-core. */
export const BRIDGE_VERSION = 1;

/** Everything the `/health` document carries. */
export interface HealthDocument {
    plugin: string;
    port: number;
    allowedOrigins: number;
    tokenRequired: boolean;
    bridgeVersion: number;
    capabilities: string[];
}

/**
 * The document every host answers `GET /health` with.
 *
 * The keys are fixed by `webview/conformance/bridge-decisions.json` → `healthKeys`, which webview-core's
 * `HostHealth` also satisfies. This host used to answer with an inline `{plugin, port, allowedOrigins,
 * tokenRequired}` and the sidecar with three of those four, so a page could not read the same fields from
 * two hosts. `bridgeVersion` and `capabilities` are additive: the four original keys keep their names and
 * types.
 *
 * `capabilities` is sorted so two hosts with the same abilities produce identical bytes.
 */
export function healthDocument(
    port: number,
    allowedOriginCount: number,
    tokenRequired: boolean,
    capabilities: readonly string[]
): HealthDocument {
    if (port < 0) {
        throw new Error(`a bound port cannot be negative, was ${port}`);
    }
    if (allowedOriginCount < 0) {
        throw new Error(`an origin count cannot be negative, was ${allowedOriginCount}`);
    }
    return {
        plugin: PLUGIN_VSCODE,
        port,
        allowedOrigins: allowedOriginCount,
        tokenRequired,
        bridgeVersion: BRIDGE_VERSION,
        capabilities: [...capabilities].sort()
    };
}

/** How many origins a comma-separated list names; 0 means every caller is refused. */
export function countAllowedOrigins(commaSeparated: string | undefined): number {
    return parseAllowedOrigins(commaSeparated).length;
}

/** What the bridge should do with one request. */
export type Route =
    | { action: 'health' }
    | { action: 'serve'; path: string; filePath: string; corsGrant: string | null }
    | { action: 'open'; filePath: string; line: number; column: number; corsGrant: string | null }
    | { action: 'options'; corsGrant: string | null }
    | { action: 'forbidden'; reason: 'origin' | 'rate' }
    | { action: 'notFound' }
    | { action: 'methodNotAllowed' };

/** The query parameters this bridge reads, already parsed. */
export interface RouteQuery {
    filePath?: string | string[];
    line?: string | string[];
    column?: string | string[];
}

function first(value: string | string[] | undefined): string | undefined {
    return Array.isArray(value) ? value[0] : value;
}

function positive(value: string | string[] | undefined, fallback: number): number {
    const parsed = Number.parseInt(first(value) ?? '', 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

/**
 * The routing and authorization decision for one request, as a value.
 *
 * Extracted so the two properties that were wrong can be asserted without a VS Code runtime and without a
 * socket: an **uninvited caller never reaches `open` or `serve`** (it gets `forbidden`, with no CORS grant),
 * and `/health` answers to anyone. The HTTP layer only turns the returned value into a response.
 *
 * @param rateLimited whether the rate limiter has already refused this request
 */
export function decideRoute(
    method: string | null | undefined,
    requestPath: string | null | undefined,
    query: RouteQuery,
    config: BridgeConfig,
    origin: string | null | undefined,
    suppliedToken: string | null | undefined,
    rateLimited: boolean
): Route {
    const grant = corsGrant(config.allowedOrigins, origin);

    if (method === 'OPTIONS') {
        return { action: 'options', corsGrant: grant };
    }

    if (requestPath === '/health') {
        // The one endpoint that answers without credentials, which is what lets a page discover a bridge.
        return { action: 'health' };
    }

    if (requestPath === '/open') {
        if (method !== 'GET') {
            return { action: 'methodNotAllowed' };
        }
        // Authorization first: an uninvited caller must not reach the rate limiter, the file system, or an
        // editor. This ordering is the bug that let a request with no Origin header through.
        if (!isAuthorized(config, origin, suppliedToken)) {
            return { action: 'forbidden', reason: 'origin' };
        }
        if (rateLimited) {
            return { action: 'forbidden', reason: 'rate' };
        }
        const filePath = first(query.filePath);
        if (!filePath) {
            return { action: 'notFound' };
        }
        return {
            action: 'open',
            filePath,
            line: positive(query.line, 1),
            column: positive(query.column, 1),
            corsGrant: grant
        };
    }

    if (requestPath?.startsWith(PageRoute.PREFIX)) {
        if (method !== 'GET') {
            return { action: 'methodNotAllowed' };
        }
        if (!isAuthorized(config, origin, suppliedToken)) {
            return { action: 'forbidden', reason: 'origin' };
        }
        const encoded = requestPath.slice(PageRoute.PREFIX.length);
        if (encoded.length === 0) {
            return { action: 'notFound' };
        }
        let decoded: string;
        try {
            decoded = decodeURIComponent(encoded);
        } catch {
            // A malformed escape is a bad request, not a crash: the caller is a web page.
            return { action: 'notFound' };
        }
        return {
            action: 'serve',
            path: requestPath,
            filePath: decoded,
            corsGrant: grant
        };
    }

    return { action: 'notFound' };
}

/**
 * The route prefix for reading a file from the project, matching `PageServer.ROUTE_PREFIX` in webview-core.
 *
 * This host keeps its own file serving — it is in TypeScript and cannot call the Java class — but the two
 * implementations are held to the same decisions: `PageServerTest` covers the Java one and
 * `BridgePolicy.test.js` covers the decode-and-jail half here.
 */
export const PageRoute = { PREFIX: '/file/' };
