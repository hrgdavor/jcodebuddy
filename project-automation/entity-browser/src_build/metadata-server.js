// Source-tree indirection: the built page directory gets the real metadata server copied beside this
// file, while the source `serve.js` resolves the shared implementation through this wrapper.
export { startMetadataServer } from '../../metadata/server.js'
