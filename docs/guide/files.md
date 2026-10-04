# Files and the gallery

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`push_file` → `pushFile`).
    [Kotlin + JUnit 5](../sdk/kotlin.md#files-and-the-gallery) shows this page's examples in
    Kotlin.

| Call | Does |
|---|---|
| `device.push_file(device_path, source)` | copies bytes, or a file on the test machine, to the device |
| `device.pull_file(device_path, to=None)` | returns a device file's bytes, or writes it to the local path `to` |
| `device.add_media(source, file_name=None)` | puts a photo or video in the gallery, where gallery apps and photo pickers list it, and returns where it landed |

The bytes are streamed through the server (at most 512 MiB), so the test machine and the server
need not share a disk.

- Paths are absolute: `/data/local/tmp/…`, or shared storage such as `/sdcard/Download/…` for
  an app with storage access to read. The directory must exist.
- Tap never overwrites a file it did not create: pushing over a device file fails with
  `ServerError`, reason `DEVICE_FILE` (as do a missing directory and a pull of something that
  is not a file). Pushing again to a path this device handle pushed replaces it.
- Media names end in a photo (`jpg`, `jpeg`, `png`, `gif`, `webp`, `heic`, `heif`, `bmp`) or
  video (`mp4`, `3gp`, `webm`, `mkv`, `mov`) extension and go to `Pictures/Tap` or
  `Movies/Tap`. The media scanner must index the file, so it has to be a real image or video.
- Every pushed file and added media item is deleted on detach (and the gallery entry with it);
  nothing else on the device is touched.

```python
device.push_file("/sdcard/Download/invoice.pdf", "fixtures/invoice.pdf")
photo = device.add_media("fixtures/cat.jpg")   # "/sdcard/Pictures/Tap/cat.jpg"
device.add_media(png_bytes, "chart.png")       # bytes need a file name
app.grant_permission("android.permission.READ_MEDIA_IMAGES")

log = device.pull_file("/sdcard/Android/data/com.example.shop/files/log.txt").decode()
device.pull_file("/data/local/tmp/trace.txt", "out/trace.txt")
```
