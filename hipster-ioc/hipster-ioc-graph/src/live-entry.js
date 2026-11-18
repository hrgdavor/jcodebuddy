import { bootGraph } from './main.js'
import { loadMetadata } from './metadata-client.js'

/** The publisher stores a collection + order; the graph projection wants the original array. */
function graphData(model) {
  if (Array.isArray(model?.contexts)) {
    return model
  }
  const values = model?.contexts ?? {}
  const order = Array.isArray(model?.collectionOrder) ? model.collectionOrder : Object.keys(values)
  return {
    contexts: order.map((key) => values[key]).filter(Boolean),
    locations: model?.locations ?? {},
    locationless: model?.locationless ?? [],
  }
}

(async () => {
  const metadata = await loadMetadata({
    collection: 'contexts',
    onChange(next) {
      bootGraph(graphData(next))
    },
  })
  bootGraph(graphData(metadata.model))
})()
