package io.github.noamcohen48.tap.sdk

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AppDataHeader
import io.github.noamcohen48.tap.api.v1.AppDataPart
import io.github.noamcohen48.tap.api.v1.AppDataServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.ExportDatabaseRequest
import io.github.noamcohen48.tap.api.v1.ExportDatabaseResponse
import io.github.noamcohen48.tap.api.v1.GetCapabilitiesRequest
import io.github.noamcohen48.tap.api.v1.GetCapabilitiesResponse
import io.github.noamcohen48.tap.api.v1.ImportDatabaseRequest
import io.github.noamcohen48.tap.api.v1.ImportDatabaseResponse
import io.github.noamcohen48.tap.api.v1.ReadFileRequest
import io.github.noamcohen48.tap.api.v1.ReadFileResponse
import io.github.noamcohen48.tap.api.v1.WriteFileRequest
import io.github.noamcohen48.tap.api.v1.WriteFileResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/** Transport fixture only; host core owns filesystem rules. */
internal class FakeAppPrivateDataService : AppDataServiceGrpcKt.AppDataServiceCoroutineImplBase() {
    val files = mutableMapOf<String, ByteArray>()
    val uploads = mutableListOf<List<AppDataPart>>()
    var response: List<AppDataPart>? = null

    override suspend fun getCapabilities(request: GetCapabilitiesRequest): GetCapabilitiesResponse =
        GetCapabilitiesResponse.newBuilder().setAvailable(true).build()

    override suspend fun writeFile(requests: Flow<WriteFileRequest>): WriteFileResponse {
        uploads += requests.map { it.part }.toList()
        return WriteFileResponse.getDefaultInstance()
    }

    override suspend fun importDatabase(requests: Flow<ImportDatabaseRequest>): ImportDatabaseResponse {
        uploads += requests.map { it.part }.toList()
        return ImportDatabaseResponse.getDefaultInstance()
    }

    override fun readFile(request: ReadFileRequest): Flow<ReadFileResponse> =
        download(request.app, request.path).map {
            ReadFileResponse.newBuilder().setPart(it).build()
        }

    override fun exportDatabase(request: ExportDatabaseRequest): Flow<ExportDatabaseResponse> =
        export(request).map {
            ExportDatabaseResponse.newBuilder().setPart(it).build()
        }

    private fun export(request: ExportDatabaseRequest): Flow<AppDataPart> =
        flow {
            for (suffix in listOf("", "-wal", "-journal")) {
                val path = "databases/${request.name}$suffix"
                if (path in files) emitAll(download(request.app, path))
            }
        }

    private fun download(
        target: AppTarget,
        path: String,
    ): Flow<AppDataPart> =
        flow {
            val override = response
            if (override != null) {
                override.forEach { emit(it) }
                return@flow
            }
            val bytes = files.getValue(path)
            emit(
                AppDataPart
                    .newBuilder()
                    .setHeader(
                        AppDataHeader
                            .newBuilder()
                            .setApp(target)
                            .setPath(path)
                            .setSizeBytes(bytes.size.toLong()),
                    ).build(),
            )
            var start = 0
            while (start < bytes.size) {
                val size = minOf(1 shl 20, bytes.size - start)
                emit(AppDataPart.newBuilder().setChunk(ByteString.copyFrom(bytes, start, size)).build())
                start += size
            }
        }
}
