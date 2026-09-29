import { Code, ConnectError, createClient, createRouterTransport } from "@connectrpc/connect";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { App } from "./App";
import { StudioService } from "./gen/studio_pb";

function client(info: () => { recorder: string; format: string }) {
  const transport = createRouterTransport(({ service }) => {
    service(StudioService, {
      info,
      getRecording() {
        throw new ConnectError("nothing recorded yet", Code.NotFound);
      },
    });
  });
  return createClient(StudioService, transport);
}

afterEach(cleanup);

describe("App", () => {
  it("shows which studio it is connected to", async () => {
    render(<App client={client(() => ({ recorder: "tap-studio 0.0.1", format: "tap-recording/1" }))} />);
    expect(await screen.findByText("tap-studio 0.0.1 · writes tap-recording/1")).toBeTruthy();
  });

  it("says when the studio cannot be reached", async () => {
    render(
      <App
        client={client(() => {
          throw new ConnectError("down", Code.Unavailable);
        })}
      />,
    );
    expect((await screen.findByRole("status")).textContent).toBe("Cannot reach tap-studio: [unavailable] down");
  });
});
