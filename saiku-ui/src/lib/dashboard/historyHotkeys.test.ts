/*
 * Unit tests for the undo / redo keyboard predicates (issue #914).
 */

import { describe, test, expect } from 'vitest';
import { isUndoKey, isRedoKey } from '$lib/dashboard/historyHotkeys';

const input = { tagName: 'INPUT' } as unknown as EventTarget;
const div = { tagName: 'DIV', isContentEditable: false } as unknown as EventTarget;
const editable = { tagName: 'DIV', isContentEditable: true } as unknown as EventTarget;

describe('isUndoKey', () => {
	test('Ctrl+Z outside a field is undo', () => {
		expect(isUndoKey({ key: 'z', ctrlKey: true, target: div })).toBe(true);
	});

	test('Cmd+Z outside a field is undo', () => {
		expect(isUndoKey({ key: 'z', metaKey: true, target: div })).toBe(true);
	});

	test('uppercase Z (caps lock) still matches', () => {
		expect(isUndoKey({ key: 'Z', ctrlKey: true, target: div })).toBe(true);
	});

	test("Ctrl+Shift+Z is NOT undo (that's redo)", () => {
		expect(isUndoKey({ key: 'z', ctrlKey: true, shiftKey: true, target: div })).toBe(false);
	});

	test('bare Z is not undo', () => {
		expect(isUndoKey({ key: 'z', target: div })).toBe(false);
	});

	test('Alt+Ctrl+Z is not undo', () => {
		expect(isUndoKey({ key: 'z', ctrlKey: true, altKey: true, target: div })).toBe(false);
	});

	test('ignored inside an input', () => {
		expect(isUndoKey({ key: 'z', ctrlKey: true, target: input })).toBe(false);
	});

	test('ignored inside contenteditable', () => {
		expect(isUndoKey({ key: 'z', ctrlKey: true, target: editable })).toBe(false);
	});
});

describe('isRedoKey', () => {
	test('Ctrl+Shift+Z outside a field is redo', () => {
		expect(isRedoKey({ key: 'z', ctrlKey: true, shiftKey: true, target: div })).toBe(true);
	});

	test('Cmd+Shift+Z outside a field is redo', () => {
		expect(isRedoKey({ key: 'z', metaKey: true, shiftKey: true, target: div })).toBe(true);
	});

	test('Ctrl+Y (no shift) is redo', () => {
		expect(isRedoKey({ key: 'y', ctrlKey: true, target: div })).toBe(true);
	});

	test('Ctrl+Shift+Y is NOT redo (avoid double-binding)', () => {
		expect(isRedoKey({ key: 'y', ctrlKey: true, shiftKey: true, target: div })).toBe(false);
	});

	test('Ctrl+Z (no shift) is NOT redo', () => {
		expect(isRedoKey({ key: 'z', ctrlKey: true, target: div })).toBe(false);
	});

	test('ignored inside an input', () => {
		expect(isRedoKey({ key: 'z', ctrlKey: true, shiftKey: true, target: input })).toBe(false);
	});
});

describe('undo / redo are mutually exclusive', () => {
	test('no event is both undo and redo', () => {
		const events = [
			{ key: 'z', ctrlKey: true, target: div },
			{ key: 'z', ctrlKey: true, shiftKey: true, target: div },
			{ key: 'y', ctrlKey: true, target: div },
			{ key: 'z', metaKey: true, target: div },
			{ key: 'z', metaKey: true, shiftKey: true, target: div }
		];
		for (const e of events) {
			expect(isUndoKey(e) && isRedoKey(e)).toBe(false);
		}
	});
});
