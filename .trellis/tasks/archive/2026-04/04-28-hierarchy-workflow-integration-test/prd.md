# Hierarchy Workflow Integration Test

## Goal

Verify a real multi-level Beads-backed Trellis hierarchy.

## Structure

* Parent: hierarchy workflow integration test
* Child: discovery branch
* Child: implementation branch
* Grandchild: implementation detail

## Acceptance Criteria

* [x] Beads creates native parent-child IDs and relationships.
* [x] Trellis list renders children through metadata snapshots.
* [x] A grandchild can be started and shown as `in_progress`.
* [x] All hierarchy test tasks can be archived and closed in Beads.
