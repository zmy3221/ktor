package io.ktor.client.backend.apache

import io.ktor.client.utils.*
import io.ktor.network.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import org.apache.http.*
import org.apache.http.entity.*
import org.apache.http.nio.*
import org.apache.http.nio.protocol.*
import org.apache.http.protocol.*
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.*
import kotlin.concurrent.*

private val MAX_QUEUE_LENGTH: Int = 65 * 1024 / DEFAULT_RESPONSE_SIZE

private data class ApacheResponseChunk(val buffer: ByteBuffer, val io: IOControl?)

internal class ApacheResponseConsumer(
        private val channel: ByteWriteChannel,
        private val block: (ApacheResponse) -> Unit
) : AbstractAsyncResponseConsumer<Unit>() {
    private val backendChannel = Channel<ApacheResponseChunk>(Channel.UNLIMITED)
    private var current: ByteBuffer = HTTP_CLIENT_RESPONSE_POOL.borrow()
    private val released = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private var channelSize: Int = 0

    init {
        runResponseProcessing()
    }

    override fun onResponseReceived(response: HttpResponse) = block(ApacheResponse(response, Closeable { release() }))

    override fun releaseResources() = Unit

    fun release(throwable: Throwable? = null) {
        if (!released.compareAndSet(false, true)) return

        try {
            if (current.position() > 0) {
                current.flip()
                if (!backendChannel.offer(ApacheResponseChunk(current, null))) {
                    HTTP_CLIENT_RESPONSE_POOL.recycle(current)
                    throw IOException("backendChannel.offer() failed")
                }
            } else HTTP_CLIENT_RESPONSE_POOL.recycle(current)
        } finally {
            backendChannel.close(throwable)
        }
    }

    override fun buildResult(context: HttpContext) = Unit

    override fun onContentReceived(decoder: ContentDecoder, ioctrl: IOControl) {
        val read = decoder.read(current)
        if (read <= 0 || current.hasRemaining()) return

        current.flip()
        if (!backendChannel.offer(ApacheResponseChunk(current, ioctrl))) {
            throw IOException("backendChannel.offer() failed")
        }

        current = HTTP_CLIENT_RESPONSE_POOL.borrow()
        lock.withLock {
            ++channelSize
            if (channelSize == MAX_QUEUE_LENGTH) ioctrl.suspendInput()
        }
    }

    override fun onEntityEnclosed(entity: HttpEntity, contentType: ContentType) {}

    private fun runResponseProcessing() = launch(ioCoroutineDispatcher) {
        try {
            while (!backendChannel.isClosedForReceive) {
                val (buffer, io) = backendChannel.receiveOrNull() ?: break
                lock.withLock {
                    --channelSize
                    io?.requestInput()
                }

                channel.writeFully(buffer)
                HTTP_CLIENT_RESPONSE_POOL.recycle(buffer)
            }
        } catch (throwable: Throwable) {
            channel.close(throwable)
        } finally {
            channel.close()
        }
    }
}
