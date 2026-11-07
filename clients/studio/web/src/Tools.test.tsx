import { create, toJsonString } from "@bufbuild/protobuf";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { StudioClient } from "./api";
import { AppQuery, DatabaseBundleSchema, DiagnosticKind, PrivateDataOperation } from "./gen/studio_pb";
import { Tools } from "./Tools";

beforeEach(() => {
  URL.createObjectURL = vi.fn(() => "blob:tool");
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function setup() {
  const calls = {
    queryApp: vi.fn().mockResolvedValue({ json: "true" }),
    privateData: vi.fn().mockResolvedValue({ json: "completed", data: new Uint8Array() }),
    exportDiagnostic: vi.fn().mockResolvedValue({ data: new Uint8Array([1, 2]), filename: "events.json", contentType: "application/json" }),
    perform: vi.fn(),
  };
  const client = calls as unknown as StudioClient;
  const props = { client, appPackage: "com.example", onAppPackage: vi.fn(), disabled: false, onBusy: vi.fn() };
  const view = render(<Tools {...props} />);
  fireEvent.click(screen.getByText("Tools (not recorded)"));
  return { calls, props, view };
}

it("does not query or mutate automatically and forwards process/permission options", async () => {
  const { calls } = setup();
  expect(calls.queryApp).not.toHaveBeenCalled();
  expect(calls.privateData).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("App query"), { target: { value: AppQuery.PROCESS } });
  fireEvent.change(screen.getByLabelText("Process query budget (ms)"), { target: { value: "123" } });
  fireEvent.click(screen.getByRole("button", { name: "Read app state" }));
  await waitFor(() => expect(calls.queryApp).toHaveBeenCalledWith({ packageName: "com.example", query: AppQuery.PROCESS, permission: undefined, timeoutMs: 123n }));
  await screen.findByText("true");
  fireEvent.change(screen.getByLabelText("App query"), { target: { value: AppQuery.PERMISSION } });
  fireEvent.change(screen.getByLabelText("Permission to query"), { target: { value: "android.permission.CAMERA" } });
  fireEvent.click(screen.getByRole("button", { name: "Read app state" }));
  await waitFor(() => expect(calls.queryApp.mock.calls[1]![0]).toMatchObject({ query: AppQuery.PERMISSION, permission: "android.permission.CAMERA", timeoutMs: undefined }));
  expect(calls.perform).not.toHaveBeenCalled();
});

it("requires explicit mutation consent and forwards binary data, overwrite and budget once", async () => {
  const { calls, props } = setup();
  fireEvent.change(screen.getByLabelText("Private data operation"), { target: { value: PrivateDataOperation.PRIVATE_DATA_WRITE } });
  fireEvent.change(screen.getByLabelText("Relative private path"), { target: { value: "files/state.bin" } });
  const bytes = new Uint8Array([0, 255, 3]);
  const file = new File([bytes], "state.bin");
  Object.defineProperty(file, "arrayBuffer", { value: async () => bytes.buffer });
  fireEvent.change(screen.getByLabelText("Private file upload"), { target: { files: [file] } });
  expect((screen.getByRole("button", { name: "Run private data tool" }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByLabelText("Overwrite existing data (not restored on release)"));
  fireEvent.change(screen.getByLabelText("Private transfer budget (ms)"), { target: { value: "1500" } });
  fireEvent.click(screen.getByLabelText("I explicitly stopped the app and confirm this private-data mutation"));
  fireEvent.click(screen.getByRole("button", { name: "Run private data tool" }));
  await waitFor(() => expect(calls.privateData).toHaveBeenCalledOnce());
  expect(calls.privateData.mock.calls[0]![0]).toMatchObject({ packageName: "com.example", path: "files/state.bin", data: bytes, confirmed: true, overwrite: true, timeoutMs: 1500n });
  await screen.findByText("completed");
  expect(props.onBusy).toHaveBeenCalledWith(true);
  expect(props.onBusy).toHaveBeenLastCalledWith(false);
  expect(calls.perform).not.toHaveBeenCalled();
});

it("never carries private mutation consent to a different package", () => {
  const { props, view } = setup();
  fireEvent.change(screen.getByLabelText("Private data operation"), { target: { value: PrivateDataOperation.PRIVATE_DATA_DELETE } });
  fireEvent.change(screen.getByLabelText("Relative private path"), { target: { value: "files/state.bin" } });
  fireEvent.click(screen.getByLabelText("I explicitly stopped the app and confirm this private-data mutation"));
  expect((screen.getByRole("button", { name: "Run private data tool" }) as HTMLButtonElement).disabled).toBe(false);
  view.rerender(<Tools {...props} appPackage="com.another" />);
  expect((screen.getByRole("button", { name: "Run private data tool" }) as HTMLButtonElement).disabled).toBe(true);
  view.rerender(<Tools {...props} />);
  expect((screen.getByRole("button", { name: "Run private data tool" }) as HTMLButtonElement).disabled).toBe(true);
});

it("imports a portable bundle with WAL presence intact and never silently retries a failure", async () => {
  const { calls } = setup();
  const bundle = create(DatabaseBundleSchema, { format: "tap-studio-database/1", name: "shop.db", database: new Uint8Array([1]), wal: new Uint8Array(), journal: new Uint8Array([2]) });
  const json = toJsonString(DatabaseBundleSchema, bundle);
  const file = new File([json], "shop.tap-database.json");
  Object.defineProperty(file, "text", { value: async () => json });
  calls.privateData.mockRejectedValue(new Error("transport lost"));
  fireEvent.change(screen.getByLabelText("Private data operation"), { target: { value: PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE } });
  fireEvent.change(screen.getByLabelText("Database bundle upload"), { target: { files: [file] } });
  fireEvent.change(screen.getByLabelText("Database basename"), { target: { value: "restored.db" } });
  fireEvent.click(screen.getByLabelText("I explicitly stopped the app and confirm this private-data mutation"));
  fireEvent.click(screen.getByRole("button", { name: "Run private data tool" }));
  await screen.findByRole("alert");
  expect(calls.privateData).toHaveBeenCalledOnce();
  expect(calls.privateData.mock.calls[0]![0].bundle).toEqual(bundle);
  expect(calls.privateData.mock.calls[0]![0]).toMatchObject({ name: "restored.db", overwrite: false });
  expect(screen.getByRole("alert").textContent).toContain("Do not repeat a mutation automatically");
});

it("rejects oversized uploads locally and leaves the SDK untouched", async () => {
  const { calls } = setup();
  fireEvent.change(screen.getByLabelText("Private data operation"), { target: { value: PrivateDataOperation.PRIVATE_DATA_WRITE } });
  fireEvent.change(screen.getByLabelText("Relative private path"), { target: { value: "files/large" } });
  fireEvent.change(screen.getByLabelText("Private file upload"), { target: { files: [new File([new Uint8Array((1 << 20) + 1)], "large")] } });
  fireEvent.click(screen.getByLabelText("I explicitly stopped the app and confirm this private-data mutation"));
  fireEvent.click(screen.getByRole("button", { name: "Run private data tool" }));
  await screen.findByRole("alert");
  expect(calls.privateData).not.toHaveBeenCalled();
});

it("downloads incremental raw history with bigint sequence and safe browser output", async () => {
  const { calls } = setup();
  fireEvent.change(screen.getByLabelText("Diagnostic download"), { target: { value: DiagnosticKind.DIAGNOSTIC_EVENTS } });
  fireEvent.change(screen.getByLabelText("After event sequence"), { target: { value: "9007199254740993" } });
  fireEvent.click(screen.getByRole("button", { name: "Download diagnostic" }));
  await screen.findByText(/Diagnostic download requested/);
  expect(calls.exportDiagnostic).toHaveBeenCalledWith({ kind: DiagnosticKind.DIAGNOSTIC_EVENTS, afterSeq: 9007199254740993n });
  expect(URL.createObjectURL).toHaveBeenCalledOnce();
  expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledOnce();
  expect(calls.perform).not.toHaveBeenCalled();
});
