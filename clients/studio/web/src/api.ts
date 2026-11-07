import { createClient, type Client, type Interceptor } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-web";
import { StudioService } from "./gen/studio_pb";

export type StudioClient = Client<typeof StudioService>;

/** This page load. A reload is a new tab to the server; control follows it (`WatchSession`). */
export const TAB_ID = crypto.randomUUID();

const namesTab: Interceptor = (next) => (request) => {
  request.header.set("x-tap-studio-tab", TAB_ID);
  return next(request);
};

/** StudioService on the page's own origin; the launch cookie authenticates every call. */
export function studioClient(): StudioClient {
  return createClient(
    StudioService,
    // GET for side-effect-free methods: plain reads, which the server does not origin-check.
    createConnectTransport({ baseUrl: window.location.origin, useHttpGet: true, interceptors: [namesTab] }),
  );
}
