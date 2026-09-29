import { createClient, type Client } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-web";
import { StudioService } from "./gen/studio_pb";

export type StudioClient = Client<typeof StudioService>;

/** StudioService on the page's own origin; the launch cookie authenticates every call. */
export function studioClient(): StudioClient {
  return createClient(
    StudioService,
    // GET for side-effect-free methods: plain reads, which the server does not origin-check.
    createConnectTransport({ baseUrl: window.location.origin, useHttpGet: true }),
  );
}
