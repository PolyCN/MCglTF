package com.poly.mcgltf.animation
import de.javagl.jgltf.model.AccessorFloatData
import de.javagl.jgltf.model.AnimationModel
import de.javagl.jgltf.model.AnimationModel.Interpolation
import de.javagl.jgltf.model.NodeModel
import org.slf4j.LoggerFactory
object GltfAnimationCreator {
    private val LOGGER = LoggerFactory.getLogger("MCglTF-Animation")
    fun createGltfAnimation(animationModel: AnimationModel): List<InterpolatedChannel> {
        val channels = animationModel.channels
        val result = ArrayList<InterpolatedChannel>(channels.size)
        for (channel in channels) {
            val sampler = channel.sampler
            val inputData = sampler.input.accessorData
            if (inputData !is AccessorFloatData) {
                LOGGER.warn("Input data is not AccessorFloatData but ${inputData.javaClass}")
                continue
            }
            val outputData = sampler.output.accessorData
            if (outputData !is AccessorFloatData) {
                LOGGER.warn("Output data is not AccessorFloatData but ${outputData.javaClass}")
                continue
            }
            val numKeyElements = inputData.numElements
            val keys = FloatArray(numKeyElements) { inputData.get(it) }
            val nodeModel = channel.nodeModel
            val path = channel.path
            val interpolation = sampler.interpolation
            when (path) {
                "translation" -> createChannel(interpolation, keys, outputData, numKeyElements, nodeModel, result) {
                    getOrCreateTranslation(it, outputData.numComponentsPerElement)
                }
                "rotation" -> createRotationChannel(interpolation, keys, outputData, numKeyElements, nodeModel, result)
                "scale" -> createChannel(interpolation, keys, outputData, numKeyElements, nodeModel, result) {
                    getOrCreateScale(it, outputData.numComponentsPerElement)
                }
                "weights" -> createWeightsChannel(interpolation, keys, outputData, numKeyElements, nodeModel, result)
                else -> LOGGER.warn("Unsupported animation channel target path: $path")
            }
        }
        return result
    }
    private inline fun createChannel(
        interpolation: Interpolation,
        keys: FloatArray,
        outputData: AccessorFloatData,
        numKeyElements: Int,
        nodeModel: NodeModel,
        result: MutableList<InterpolatedChannel>,
        crossinline listenerProvider: (NodeModel) -> FloatArray
    ) {
        val numComponents = outputData.numComponentsPerElement
        when (interpolation) {
            Interpolation.STEP -> {
                val values = readValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.Step(keys, values, listenerProvider(nodeModel)))
            }
            Interpolation.LINEAR -> {
                val values = readValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.Linear(keys, values, listenerProvider(nodeModel)))
            }
            Interpolation.CUBICSPLINE -> {
                val values = readCubicValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.CubicSpline(keys, values, listenerProvider(nodeModel)))
            }
            else -> LOGGER.warn("Unsupported interpolation: $interpolation")
        }
    }
    private fun createRotationChannel(
        interpolation: Interpolation,
        keys: FloatArray,
        outputData: AccessorFloatData,
        numKeyElements: Int,
        nodeModel: NodeModel,
        result: MutableList<InterpolatedChannel>
    ) {
        val numComponents = outputData.numComponentsPerElement
        val listener = getOrCreateRotation(nodeModel, numComponents)
        when (interpolation) {
            Interpolation.STEP -> {
                val values = readValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.Step(keys, values, listener))
            }
            Interpolation.LINEAR -> {
                val values = readValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.SphericalLinear(keys, values, listener))
            }
            Interpolation.CUBICSPLINE -> {
                val values = readCubicValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.CubicSpline(keys, values, listener))
            }
            else -> LOGGER.warn("Unsupported interpolation: $interpolation")
        }
    }
    private fun createWeightsChannel(
        interpolation: Interpolation,
        keys: FloatArray,
        outputData: AccessorFloatData,
        numKeyElements: Int,
        nodeModel: NodeModel,
        result: MutableList<InterpolatedChannel>
    ) {
        val totalComponents = outputData.numElements * outputData.numComponentsPerElement
        when (interpolation) {
            Interpolation.STEP -> {
                val numComponents = totalComponents / numKeyElements
                val values = readValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.Step(keys, values, getOrCreateWeights(nodeModel, numComponents)))
            }
            Interpolation.LINEAR -> {
                val numComponents = totalComponents / numKeyElements
                val values = readValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.Linear(keys, values, getOrCreateWeights(nodeModel, numComponents)))
            }
            Interpolation.CUBICSPLINE -> {
                val numComponents = totalComponents / numKeyElements / 3
                val values = readCubicValues(outputData, numKeyElements, numComponents)
                result.add(InterpolatedChannel.CubicSpline(keys, values, getOrCreateWeights(nodeModel, numComponents)))
            }
            else -> LOGGER.warn("Unsupported interpolation: $interpolation")
        }
    }
    private fun readValues(data: AccessorFloatData, numKeyElements: Int, numComponents: Int): Array<FloatArray> {
        var globalIndex = 0
        return Array(numKeyElements) {
            FloatArray(numComponents) { data.get(globalIndex++) }
        }
    }
    private fun readCubicValues(data: AccessorFloatData, numKeyElements: Int, numComponents: Int): Array<Array<FloatArray>> {
        var globalIndex = 0
        return Array(numKeyElements) {
            Array(3) {
                FloatArray(numComponents) { data.get(globalIndex++) }
            }
        }
    }
    private fun getOrCreateTranslation(nodeModel: NodeModel, size: Int): FloatArray {
        var t = nodeModel.translation
        if (t == null) { t = FloatArray(size); nodeModel.setTranslation(t) }
        return t
    }
    private fun getOrCreateRotation(nodeModel: NodeModel, size: Int): FloatArray {
        var r = nodeModel.rotation
        if (r == null) { r = FloatArray(size); nodeModel.setRotation(r) }
        return r
    }
    private fun getOrCreateScale(nodeModel: NodeModel, size: Int): FloatArray {
        var s = nodeModel.scale
        if (s == null) { s = FloatArray(size); nodeModel.setScale(s) }
        return s
    }
    private fun getOrCreateWeights(nodeModel: NodeModel, size: Int): FloatArray {
        var w = nodeModel.weights
        if (w == null) { w = FloatArray(size); nodeModel.setWeights(w) }
        return w
    }
}