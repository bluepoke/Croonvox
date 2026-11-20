// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separation.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer

/**
 * [TensorModel] on ONNX Runtime (`onnxruntime-android` on device, the desktop build in tests),
 * configured as ADR 15 decided: CPU execution provider, [threads] threads, with the CPU arena and
 * memory-pattern optimisation off (that lowers the peak memory by hundreds of MB at the same speed).
 */
class OrtTensorModel(file: File, threads: Int = 4) : TensorModel {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { so ->
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        so.setCPUArenaAllocator(false)
        so.setMemoryPatternOptimization(false)
        so.setIntraOpNumThreads(threads)
        env.createSession(file.absolutePath, so)
    }

    override val inputs: Map<String, LongArray> = session.inputInfo.mapValues { (it.value.info as TensorInfo).shape }
    override val outputs: Map<String, LongArray> = session.outputInfo.mapValues { (it.value.info as TensorInfo).shape }

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val onnx = inputs.mapValues { (_, t) -> OnnxTensor.createTensor(env, FloatBuffer.wrap(t.data), t.shape) }
        try {
            session.run(onnx).use { result ->
                return result.associate { (name, value) ->
                    val tensor = value as OnnxTensor
                    val buffer = tensor.floatBuffer
                    val data = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    name to Tensor(tensor.info.shape, data)
                }
            }
        } finally {
            onnx.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()
}
