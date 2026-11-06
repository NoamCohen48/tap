package io.github.noamcohen48.tap.daemon.grpc

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AppCall
import io.github.noamcohen48.tap.api.v1.AppDataEntry
import io.github.noamcohen48.tap.api.v1.AppDataHeader
import io.github.noamcohen48.tap.api.v1.AppDataPart
import io.github.noamcohen48.tap.api.v1.AppDataServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.DeleteFileRequest
import io.github.noamcohen48.tap.api.v1.DeleteFileResponse
import io.github.noamcohen48.tap.api.v1.ExportDatabaseRequest
import io.github.noamcohen48.tap.api.v1.ExportDatabaseResponse
import io.github.noamcohen48.tap.api.v1.GetCapabilitiesRequest
import io.github.noamcohen48.tap.api.v1.GetCapabilitiesResponse
import io.github.noamcohen48.tap.api.v1.ImportDatabaseRequest
import io.github.noamcohen48.tap.api.v1.ImportDatabaseResponse
import io.github.noamcohen48.tap.api.v1.ListFilesRequest
import io.github.noamcohen48.tap.api.v1.ListFilesResponse
import io.github.noamcohen48.tap.api.v1.ReadFileRequest
import io.github.noamcohen48.tap.api.v1.ReadFileResponse
import io.github.noamcohen48.tap.api.v1.WriteFileRequest
import io.github.noamcohen48.tap.api.v1.WriteFileResponse
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.AppPrivateData
import io.github.noamcohen48.tap.host.PrivateDataFile
import io.github.noamcohen48.tap.host.isDriverPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Authenticated, owner-scoped private data. Event records contain no paths or transferred bytes. */
class AppDataService(
    private val daemon: TapDaemon,
) : AppDataServiceGrpcKt.AppDataServiceCoroutineImplBase() {
    private suspend fun data(target: AppTarget): AppPrivateData {
        argument(target.packageName.isNotBlank()) { "package_name is required" }
        argument(!isDriverPackage(target.packageName)) { "Tap's driver packages are protected" }
        return daemon
            .attachedDevice(target.attachedDeviceId, target.clientConnectionId)
            .deviceSession
            .app(target.packageName)
            .data
    }

    private suspend fun <T> logged(
        target: AppTarget,
        operation: String,
        block: suspend (AppPrivateData) -> T,
    ): T {
        val data = data(target)
        val device = daemon.attachedDevice(target.attachedDeviceId, target.clientConnectionId)
        val call =
            AppCall
                .newBuilder()
                .setPackageName(target.packageName)
                .setOperation(operation)
                .build()
        return device.recorded({ setApp(call) }) { block(data) }
    }

    override suspend fun getCapabilities(request: GetCapabilitiesRequest): GetCapabilitiesResponse =
        reply {
            val access = logged(request.app, "app_data_capabilities") { it.capabilities() }
            GetCapabilitiesResponse
                .newBuilder()
                .setAvailable(access.available)
                .apply { access.reason?.let { reason = it } }
                .build()
        }

    override suspend fun listFiles(request: ListFilesRequest): ListFilesResponse =
        reply {
            AppPrivateData.validatePath(request.path, allowRoot = true)
            val entries = logged(request.app, "app_data_list") { it.list(request.path) }
            ListFilesResponse
                .newBuilder()
                .addAllEntries(
                    entries.map {
                        AppDataEntry
                            .newBuilder()
                            .setName(
                                it.name,
                            ).setSizeBytes(it.sizeBytes)
                            .setIsDirectory(it.isDirectory)
                            .setIsSymlink(it.isSymlink)
                            .build()
                    },
                ).build()
        }

    override fun readFile(request: ReadFileRequest): Flow<ReadFileResponse> =
        read(request).map {
            ReadFileResponse.newBuilder().setPart(it).build()
        }

    private fun read(request: ReadFileRequest): Flow<AppDataPart> =
        flow {
            reply {
                AppPrivateData.validatePath(request.path)
                data(request.app) // authorize before allocating/spooling
                spool { directory ->
                    val file = directory.resolve("download")
                    val size = logged(request.app, "app_data_read") { it.read(request.path, file) }
                    send(request.app, request.path, file, size)
                }
            }
        }

    override suspend fun deleteFile(request: DeleteFileRequest): DeleteFileResponse =
        reply {
            AppPrivateData.validatePath(request.path)
            logged(request.app, "app_data_delete") { it.delete(request.path) }
            DeleteFileResponse.getDefaultInstance()
        }

    override suspend fun writeFile(requests: Flow<WriteFileRequest>): WriteFileResponse =
        reply {
            upload(requests.map { it.part }, database = false) { first, files ->
                logged(first.app, "app_data_write") { it.write(first.path, files.single().path, first.overwrite) }
            }
            WriteFileResponse.getDefaultInstance()
        }

    override fun exportDatabase(request: ExportDatabaseRequest): Flow<ExportDatabaseResponse> =
        export(request).map {
            ExportDatabaseResponse.newBuilder().setPart(it).build()
        }

    private fun export(request: ExportDatabaseRequest): Flow<AppDataPart> =
        flow {
            reply {
                AppPrivateData.validateDatabaseName(request.name)
                data(request.app)
                spool { directory ->
                    val files = logged(request.app, "app_data_export_database") { it.exportDatabase(request.name, directory) }
                    files.forEach { send(request.app, "databases/${it.name}", it.path, it.sizeBytes) }
                }
            }
        }

    override suspend fun importDatabase(requests: Flow<ImportDatabaseRequest>): ImportDatabaseResponse =
        reply {
            upload(requests.map { it.part }, database = true) { first, files ->
                val name = first.path.removePrefix("databases/")
                logged(first.app, "app_data_import_database") { it.importDatabase(name, files, first.overwrite) }
            }
            ImportDatabaseResponse.getDefaultInstance()
        }

    private suspend fun FlowCollector<AppDataPart>.send(
        target: AppTarget,
        path: String,
        file: Path,
        size: Long,
    ) {
        emit(
            AppDataPart
                .newBuilder()
                .setHeader(
                    AppDataHeader
                        .newBuilder()
                        .setApp(target)
                        .setPath(path)
                        .setSizeBytes(size),
                ).build(),
        )
        withContext(Dispatchers.IO) { Files.newInputStream(file) }.use { input ->
            val buffer = ByteArray(AppPrivateData.CHUNK_BYTES)
            var sent = 0L
            while (true) {
                val count = withContext(Dispatchers.IO) { input.read(buffer) }
                if (count < 0) break
                sent += count
                argument(sent <= size) { "private-data spool grew during read" }
                emit(AppDataPart.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, count)).build())
            }
            argument(sent == size) { "private-data spool ended before size_bytes" }
        }
    }

    private suspend fun upload(
        requests: Flow<AppDataPart>,
        database: Boolean,
        block: suspend (AppDataHeader, List<PrivateDataFile>) -> Unit,
    ) = spool { directory ->
        var first: AppDataHeader? = null
        var current: AppDataHeader? = null
        var received = 0L
        var declared = 0L
        var output: OutputStream? = null
        val files = mutableListOf<PrivateDataFile>()

        fun finishEntry() {
            current?.let { argument(received == it.sizeBytes) { "private-data upload ended before size_bytes" } }
        }
        try {
            requests.collect { part ->
                when (part.partCase) {
                    AppDataPart.PartCase.HEADER -> {
                        finishEntry()
                        val header = part.header
                        AppPrivateData.validatePath(header.path)
                        argument(header.sizeBytes in 0..AppPrivateData.MAX_BYTES) { "size_bytes must be in 0..${AppPrivateData.MAX_BYTES}" }
                        if (first == null) {
                            data(header.app) // reject a non-owner before accepting any bytes
                            if (database) {
                                argument(header.path.startsWith("databases/")) { "database header path must start with databases/" }
                                AppPrivateData.validateDatabaseName(header.path.removePrefix("databases/"))
                                argument(header.sizeBytes > 0) { "database must not be empty" }
                            }
                            first = header
                        } else {
                            val initial = first!!
                            argument(database && files.size < 3) { "too many private-data file headers" }
                            argument(
                                header.app == initial.app && header.overwrite == initial.overwrite,
                            ) { "all headers must use the same app and overwrite policy" }
                            argument(header.path in listOf(initial.path + "-wal", initial.path + "-journal")) {
                                "only database WAL/journal sidecars may follow the database"
                            }
                        }
                        argument(files.none { it.name == header.path.substringAfterLast('/') }) { "duplicate private-data entry" }
                        declared += header.sizeBytes
                        argument(declared <= AppPrivateData.MAX_BYTES) { "private-data upload exceeds ${AppPrivateData.MAX_BYTES} bytes" }
                        withContext(Dispatchers.IO) { output?.close() }
                        val path = directory.resolve(files.size.toString())
                        output = withContext(Dispatchers.IO) { Files.newOutputStream(path) }
                        files += PrivateDataFile(header.path.substringAfterLast('/'), path, header.sizeBytes)
                        current = header
                        received = 0
                    }

                    AppDataPart.PartCase.CHUNK -> {
                        val header = argumentNotNull(current) { "a header must precede chunks" }
                        argument(part.chunk.size() <= AppPrivateData.CHUNK_BYTES) { "private-data chunk exceeds 1 MiB" }
                        received += part.chunk.size()
                        argument(received <= header.sizeBytes) { "private-data upload exceeds size_bytes" }
                        withContext(Dispatchers.IO) { part.chunk.writeTo(output!!) }
                    }

                    else -> {
                        throw InvalidArgumentException("private-data part must be set")
                    }
                }
            }
            finishEntry()
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { output?.close() }
        }
        block(argumentNotNull(first) { "a private-data header is required" }, files)
    }

    private suspend fun <T> spool(block: suspend (Path) -> T): T {
        val directory =
            withContext(Dispatchers.IO) {
                val uploads = daemon.config.stateDir.resolve("uploads")
                Files.createDirectories(uploads)
                // JDK temp directories are owner-only; filenames in this directory are not client paths.
                Files.createTempDirectory(uploads, "private-data-")
            }
        try {
            return block(directory)
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }
}
