// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer

/** Where ONNX Runtime runs the graph. NNAPI exists only on Android (and is deprecated from Android 15). */
enum class ExecutionProvider { Cpu, Xnnpack, Nnapi }

data class RuntimeOptions(
    val provider: ExecutionProvider = ExecutionProvider.Cpu,
    val threads: Int = 4,
    /** ORT's CPU arena keeps its peak allocations for reuse: faster, but a higher resident peak. */
    val cpuArena: Boolean = true,
)

/** [TensorModel] on ONNX Runtime (`onnxruntime-android` on device, the desktop build in tests). */
class OrtTensorModel(file: File, options: RuntimeOptions = RuntimeOptions()) : TensorModel {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { so ->
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (!options.cpuArena) {
            so.setCPUArenaAllocator(false)
            so.setMemoryPatternOptimization(false)
        }
        when (options.provider) {
            ExecutionProvider.Cpu -> so.setIntraOpNumThreads(options.threads)
            ExecutionProvider.Xnnpack -> {
                // XNNPACK brings its own thread pool; ORT's should then stay at 1.
                so.setIntraOpNumThreads(1)
                so.addXnnpack(mapOf("intra_op_num_threads" to options.threads.toString()))
            }
            ExecutionProvider.Nnapi -> {
                so.setIntraOpNumThreads(options.threads)
                so.addNnapi()
            }
        }
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
