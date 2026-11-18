import contexts from './contexts.js'
import { bootGraph } from './main.js'

// The standalone page's data is inlined into contexts.js by build.js. Keeping the call here means main.js
// is also usable by the live entry, which supplies the same shape from the metadata host.
bootGraph(contexts)
