import { z } from "zod";
import type { Run } from "./model";
const dictionary = <T extends z.ZodType>(value: T) =>
  z.record(z.string(), value);
const action = z.object({
  source: z.string(),
  verb: z.string(),
  status: z.enum(["blocked", "pending", "attempted", "uncertain"]),
  target: z
    .object({
      label: z.string().optional(),
      resource: z.string().optional(),
      selector: z.unknown().optional(),
      blocked_reason: z.string().nullable().optional(),
      risk: z.string().optional(),
    })
    .passthrough(),
  scenario: z.object({ value: z.string().optional() }).passthrough(),
  approval: z.string().nullable(),
});
const proposal = z.object({
  title: z.string(),
  summary: z.string(),
  actions: z.array(
    z.object({
      candidate: z.string(),
      intent: z.string(),
      risk: z.string(),
      reason: z.string(),
    }),
  ),
});
export const runSchema: z.ZodType<Run> = z.object({
  live: z.boolean(),
  current: z.string().nullable(),
  halted: z.string().nullable(),
  observation: z.string().nullable(),
  initial_error: z.string().nullable(),
  next: z.object({ action: z.string().nullable(), reason: z.string() }),
  interpretations: dictionary(
    z.object({
      status: z.string(),
      model: z.string().optional(),
      error: z.string().optional(),
      proposal: proposal.optional(),
    }),
  ),
  graph: z.object({
    format: z.literal("tap-exploration/1"),
    context: dictionary(z.unknown()),
    states: dictionary(
      z.object({
        depth: z.number().int().nonnegative(),
        signature: z.string(),
        observations: z.array(z.string()),
      }),
    ),
    actions: dictionary(action),
    attempts: dictionary(
      z.object({
        action: z.string(),
        before: z.string(),
        after: z.string().nullable(),
        destination: z.string().nullable(),
        status: z.string(),
        note: z.string(),
      }),
    ),
    observations: dictionary(
      z.object({
        evidence: z
          .object({
            screenshot: z.string().optional(),
            snapshot: z.string().optional(),
            moving: z.boolean().optional(),
            settle_error: z.string().nullable().optional(),
            features: z
              .object({
                nodes: z
                  .array(
                    z.object({
                      text: z.string().nullable().optional(),
                      class: z.string().nullable().optional(),
                    }),
                  )
                  .optional(),
              })
              .nullable()
              .optional(),
          })
          .passthrough(),
      }),
    ),
  }),
});
