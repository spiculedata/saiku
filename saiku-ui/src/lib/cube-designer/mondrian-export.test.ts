import { describe, it, expect } from 'vitest';
import { parse } from 'yaml';
import { exportToMondrianXml, exportToMondrianYaml } from './mondrian-export.js';
import type { SchemaCanvasState } from './types.js';

function baseDoc(): SchemaCanvasState {
	return {
		version: 1,
		connectionId: 'conn-1',
		label: 'My Cube',
		tables: [],
		joins: [],
		groups: [],
		updatedAt: '2026-06-18T00:00:00.000Z'
	};
}

describe('exportToMondrianXml', () => {
	it('throws when no fact table is on the canvas', () => {
		const doc = baseDoc();
		expect(() => exportToMondrianXml(doc)).toThrow(/no fact table/);
	});

	it('emits a stub Row count measure when the doc has no measures', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [{ name: 'amount', sqlType: 'numeric' }],
					position: { x: 0, y: 0 },
					groupId: null
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		expect(xml).toContain('<Schema name="My Cube" metamodelVersion="4.0">');
		expect(xml).toContain('<Table name="sales_fact" schema="public" />');
		expect(xml).toContain('<Measure name="Row count" aggregator="count"');
		// M4 only — no legacy M3 shapes.
		expect(xml).not.toContain('foreignKey=');
		expect(xml).not.toMatch(/<Hierarchy[^>]*primaryKey=/);
	});

	it('emits user-authored measures instead of the Row count stub', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [
						{ name: 'amount', sqlType: 'numeric' },
						{ name: 'qty', sqlType: 'numeric' }
					],
					position: { x: 0, y: 0 },
					groupId: null
				}
			],
			measures: [
				{
					id: 'm1',
					name: 'Sales',
					aggregator: 'sum',
					tableId: 'f',
					columnName: 'amount',
					formatString: '$#,##0'
				},
				{
					id: 'm2',
					name: 'Orders',
					aggregator: 'count',
					tableId: 'f',
					columnName: 'qty'
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		expect(xml).toContain(
			'<Measure name="Sales" column="amount" aggregator="sum" formatString="$#,##0" />'
		);
		expect(xml).toContain('<Measure name="Orders" column="qty" aggregator="count" />');
		expect(xml).not.toContain('Row count');
	});

	it('emits median + percentile aggregators, with percentile="N" only for percentile', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [
						{ name: 'order_total', sqlType: 'numeric' },
						{ name: 'latency_ms', sqlType: 'numeric' }
					],
					position: { x: 0, y: 0 },
					groupId: null
				}
			],
			measures: [
				{
					id: 'm1',
					name: 'Median Order',
					aggregator: 'median',
					tableId: 'f',
					columnName: 'order_total'
				},
				{
					id: 'm2',
					name: 'P90 Latency',
					aggregator: 'percentile',
					tableId: 'f',
					columnName: 'latency_ms',
					percentile: 90
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		// median: aggregator only, no percentile attribute.
		expect(xml).toContain(
			'<Measure name="Median Order" column="order_total" aggregator="median" />'
		);
		// percentile: carries the fraction.
		expect(xml).toContain(
			'<Measure name="P90 Latency" column="latency_ms" aggregator="percentile" percentile="90" />'
		);
	});

	it('emits workbench-curated dimensions with their hierarchies and levels', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [{ name: 'customer_id', sqlType: 'int' }],
					position: { x: 0, y: 0 },
					groupId: null
				},
				{
					id: 'c',
					schema: 'public',
					name: 'customer',
					role: 'dimension',
					columns: [
						{ name: 'id', sqlType: 'int' },
						{ name: 'country', sqlType: 'text' },
						{ name: 'city', sqlType: 'text' }
					],
					position: { x: 100, y: 0 },
					groupId: null
				}
			],
			dimensions: [
				{
					id: 'd1',
					name: 'Customer',
					foreignKey: 'customer_id',
					primaryKey: 'id',
					primaryKeyTableId: 'c',
					hierarchies: [
						{
							id: 'h1',
							name: 'Geo',
							hasAll: true,
							levels: [
								{
									id: 'l1',
									name: 'Country',
									caption: 'Country of residence',
									tableId: 'c',
									columnName: 'country',
									type: 'String'
								},
								{
									id: 'l2',
									name: 'City',
									tableId: 'c',
									columnName: 'city',
									type: 'String'
								}
							]
						}
					],
					dimensionType: 'Standard'
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		// M4: PK on the physical dim table + dimension key; FK on the link.
		expect(xml).toContain('<Table name="customer" schema="public" keyColumn="id" />');
		expect(xml).toContain('<Dimension name="Customer" table="customer" key="Customer Key">');
		expect(xml).toContain('<Attribute name="Customer Key" keyColumn="id" hasHierarchy="false" />');
		expect(xml).toContain('<Hierarchy name="Geo" hasAll="true">');
		// Levels reference attributes synthesised from the M3-style level columns.
		expect(xml).toContain('<Attribute name="Country" keyColumn="country" />');
		expect(xml).toContain('<Attribute name="City" keyColumn="city" />');
		expect(xml).toContain('<Level attribute="Country" caption="Country of residence" />');
		expect(xml).toContain('<Level attribute="City" />');
		expect(xml).toContain('<ForeignKeyLink dimension="Customer" foreignKeyColumn="customer_id" />');
	});

	it('annotates Time dimensions with type="TIME" and per-level levelType', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: null,
					name: 'fact',
					role: 'fact',
					columns: [{ name: 'date_id', sqlType: 'int' }],
					position: { x: 0, y: 0 },
					groupId: null
				},
				{
					id: 't',
					schema: null,
					name: 'time_by_day',
					role: 'dimension',
					columns: [
						{ name: 'id', sqlType: 'int' },
						{ name: 'year', sqlType: 'int' }
					],
					position: { x: 0, y: 0 },
					groupId: null
				}
			],
			dimensions: [
				{
					id: 'd-time',
					name: 'Time',
					foreignKey: 'date_id',
					primaryKey: 'id',
					primaryKeyTableId: 't',
					dimensionType: 'Time',
					hierarchies: [
						{
							id: 'h-t',
							name: 'Time',
							hasAll: true,
							levels: [
								{
									id: 'l-y',
									name: 'Year',
									tableId: 't',
									columnName: 'year',
									type: 'Integer',
									levelType: 'TimeYears'
								}
							]
						}
					]
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		// Mondrian 4 spells it "TIME" (not the M3 "TimeDimension").
		expect(xml).toContain('type="TIME"');
		expect(xml).not.toContain('type="TimeDimension"');
		// levelType rides on the level's <Attribute> (M4 shape).
		expect(xml).toContain('levelType="TimeYears"');
	});

	it('falls back to join-inferred dimensions when no curated dims exist', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [{ name: 'customer_id', sqlType: 'int' }],
					position: { x: 0, y: 0 },
					groupId: null
				},
				{
					id: 'c',
					schema: 'public',
					name: 'customer',
					role: 'dimension',
					columns: [
						{ name: 'id', sqlType: 'int' },
						{ name: 'country', sqlType: 'text' }
					],
					position: { x: 100, y: 0 },
					groupId: null
				}
			],
			joins: [
				{
					id: 'j1',
					sourceTableId: 'f',
					sourceColumnName: 'customer_id',
					targetTableId: 'c',
					targetColumnName: 'id',
					kind: 'inner'
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		// Inferred dims are emitted in the SAME M4 shape as curated ones.
		expect(xml).toContain('<Table name="customer" schema="public" keyColumn="id" />');
		expect(xml).toContain('<Dimension name="Customer" table="customer" key="id">');
		expect(xml).toContain('<Level attribute="country" />');
		expect(xml).toContain('<ForeignKeyLink dimension="Customer" foreignKeyColumn="customer_id" />');
	});

	it('escapes special characters in identifiers', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			label: 'A&B<Cube>',
			tables: [
				{
					id: 'f',
					schema: null,
					name: 'fact',
					role: 'fact',
					columns: [],
					position: { x: 0, y: 0 },
					groupId: null
				}
			],
			measures: [
				{
					id: 'm',
					name: 'Sales & Returns',
					aggregator: 'sum',
					tableId: 'f',
					columnName: 'amount'
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		expect(xml).toContain('<Schema name="A&amp;B&lt;Cube&gt;" metamodelVersion="4.0">');
		expect(xml).toContain('<Measure name="Sales &amp; Returns"');
	});
});

describe('exportToMondrianXml — cubes-based', () => {
	function cubeDoc(): SchemaCanvasState {
		return {
			...baseDoc(),
			label: 'Sales',
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [
						{ name: 'amount', sqlType: 'numeric' },
						{ name: 'qty', sqlType: 'numeric' },
						{ name: 'customer_id', sqlType: 'int' }
					],
					position: { x: 0, y: 0 },
					groupId: null
				},
				{
					id: 'c',
					schema: 'public',
					name: 'customer',
					role: 'dimension',
					columns: [
						{ name: 'id', sqlType: 'int' },
						{ name: 'country', sqlType: 'text' }
					],
					position: { x: 100, y: 0 },
					groupId: null
				}
			],
			dimensions: [
				{
					id: 'd1',
					name: 'Customer',
					sourceTableId: 'c',
					primaryKeyTableId: 'c',
					primaryKey: 'id',
					attributes: [{ tableId: 'c', columnName: 'id' }],
					hierarchies: [
						{
							id: 'h1',
							name: 'Geo',
							hasAll: true,
							levels: [
								{
									id: 'l1',
									name: 'Country',
									tableId: 'c',
									columnName: 'country',
									type: 'String'
								}
							]
						}
					]
				}
			],
			measures: [
				{
					id: 'm1',
					name: 'Sales',
					aggregator: 'sum',
					tableId: 'f',
					columnName: 'amount',
					formatString: '$#,##0'
				}
			],
			cubes: [
				{
					id: 'cube-1',
					name: 'Sales',
					measureGroups: [
						{
							id: 'mg-1',
							name: 'Sales Facts',
							measureColumns: ['amount', 'qty'],
							factTableId: 'f',
							dimensionLinks: [{ dimensionId: 'd1', foreignKeyColumn: 'customer_id' }]
						}
					],
					calcs: [
						{
							id: 'calc-1',
							name: 'Margin',
							tokens: [
								{ kind: 'measure', name: 'Sales' },
								{ kind: 'op', op: '-' },
								{ kind: 'measure', name: 'Cost' }
							],
							mode: 'build'
						}
					]
				}
			]
		};
	}

	it('emits a Cube + MeasureGroup from state.cubes (not the flat fallback)', () => {
		const xml = exportToMondrianXml(cubeDoc());
		expect(xml).toContain('<Cube name="Sales">');
		expect(xml).toContain('<MeasureGroup name="Sales Facts" table="sales_fact">');
		// Measure name is the column (matches the Code-tab preview); aggregator
		// + formatString come from state.measures.
		expect(xml).toContain(
			'<Measure name="amount" column="amount" aggregator="sum" formatString="$#,##0" />'
		);
		// A column with no matching state.measures entry defaults to sum.
		expect(xml).toContain('<Measure name="qty" column="qty" aggregator="sum" />');
	});

	it('emits the measure group dimension link', () => {
		const xml = exportToMondrianXml(cubeDoc());
		expect(xml).toContain('<ForeignKeyLink dimension="Customer" foreignKeyColumn="customer_id" />');
	});

	it('emits cube-scope calculated members from calc tokens', () => {
		const xml = exportToMondrianXml(cubeDoc());
		expect(xml).toContain(
			'<CalculatedMember name="Margin" dimension="Measures" formula="[Sales] - [Cost]"/>'
		);
	});

	it('emits <Annotations> (saiku.semantic.*) on a measure', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'fact',
					role: 'fact',
					columns: [{ name: 'rev', sqlType: 'numeric' }],
					position: { x: 0, y: 0 },
					groupId: null
				}
			],
			measures: [
				{
					id: 'm1',
					name: 'rev',
					aggregator: 'sum',
					tableId: 'f',
					columnName: 'rev',
					annotations: { pii: 'true', unit: 'USD' }
				}
			],
			cubes: [
				{
					id: 'c1',
					name: 'C',
					measureGroups: [
						{
							id: 'g1',
							name: 'G',
							measureColumns: ['rev'],
							factTableId: 'f',
							dimensionLinks: []
						}
					],
					calcs: []
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		expect(xml).toContain('<Measure name="rev" column="rev" aggregator="sum">');
		expect(xml).toContain('<Annotation name="saiku.semantic.pii">true</Annotation>');
		expect(xml).toContain('<Annotation name="saiku.semantic.unit">USD</Annotation>');
	});

	it('emits <Attribute> overrides and cascades a rename to the <Level> reference', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'fact',
					role: 'fact',
					columns: [{ name: 'cust_id', sqlType: 'int' }],
					position: { x: 0, y: 0 },
					groupId: null
				},
				{
					id: 'c',
					schema: 'public',
					name: 'customer',
					role: 'dimension',
					columns: [
						{ name: 'id', sqlType: 'int' },
						{ name: 'country', sqlType: 'text' },
						{ name: 'country_label', sqlType: 'text' },
						{ name: 'country_sort', sqlType: 'int' }
					],
					position: { x: 100, y: 0 },
					groupId: null
				}
			],
			dimensions: [
				{
					id: 'd1',
					name: 'Customer',
					foreignKey: 'cust_id',
					primaryKey: 'id',
					primaryKeyTableId: 'c',
					attributes: [
						{
							tableId: 'c',
							columnName: 'country',
							name: 'Country Name',
							nameColumn: 'country_label',
							orderByColumn: 'country_sort',
							description: 'ISO country'
						}
					],
					hierarchies: [
						{
							id: 'h1',
							name: 'Geo',
							hasAll: true,
							// The level is stored by column; the emit must reference the
							// attribute by its (renamed) NAME.
							levels: [
								{
									id: 'l1',
									name: 'Country',
									tableId: 'c',
									columnName: 'country'
								}
							]
						}
					]
				}
			],
			cubes: [
				{
					id: 'cube1',
					name: 'C',
					measureGroups: [
						{
							id: 'g1',
							name: 'G',
							measureColumns: [],
							factTableId: 'f',
							dimensionLinks: []
						}
					],
					calcs: []
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		expect(xml).toContain(
			'<Attribute name="Country Name" keyColumn="country" nameColumn="country_label" orderByColumn="country_sort" description="ISO country" />'
		);
		// The rename cascaded: the level references the attribute by its new name.
		expect(xml).toContain('<Level attribute="Country Name" />');
	});

	it('emits <Hierarchy> caption + allMemberName from the inspector', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'fact',
					role: 'fact',
					columns: [{ name: 'cust_id', sqlType: 'int' }],
					position: { x: 0, y: 0 },
					groupId: null
				},
				{
					id: 'c',
					schema: 'public',
					name: 'customer',
					role: 'dimension',
					columns: [
						{ name: 'id', sqlType: 'int' },
						{ name: 'country', sqlType: 'text' }
					],
					position: { x: 100, y: 0 },
					groupId: null
				}
			],
			dimensions: [
				{
					id: 'd1',
					name: 'Customer',
					foreignKey: 'cust_id',
					primaryKey: 'id',
					primaryKeyTableId: 'c',
					hierarchies: [
						{
							id: 'h1',
							name: 'Geo',
							hasAll: true,
							caption: 'Customer Geography',
							allMemberName: 'All Customers',
							levels: [
								{
									id: 'l1',
									name: 'Country',
									tableId: 'c',
									columnName: 'country',
									type: 'String'
								}
							]
						}
					]
				}
			],
			cubes: [
				{
					id: 'cube1',
					name: 'C',
					measureGroups: [
						{
							id: 'g1',
							name: 'G',
							measureColumns: [],
							factTableId: 'f',
							dimensionLinks: []
						}
					],
					calcs: []
				}
			]
		};
		const xml = exportToMondrianXml(doc);
		expect(xml).toContain('allMemberName="All Customers"');
		expect(xml).toContain('caption="Customer Geography"');
	});

	it('emits <TimeCalcs> for cube-scoped time-intelligence metrics', () => {
		const doc = cubeDoc();
		doc.cubes![0].timeCalcs = [
			{
				id: 't1',
				name: 'Amount YoY',
				type: 'yoy',
				measure: 'amount',
				formatString: '0.0%'
			},
			{
				id: 't2',
				name: 'Amount R3',
				type: 'rolling',
				measure: 'amount',
				window: 3,
				function: 'avg'
			},
			// Half-authored (no measure) — must be dropped, not emitted invalid.
			{ id: 't3', name: 'Incomplete', type: 'ytd', measure: '' }
		];
		const xml = exportToMondrianXml(doc);
		expect(xml).toContain('<TimeCalcs>');
		expect(xml).toContain(
			'<TimeCalc name="Amount YoY" type="yoy" measure="amount" formatString="0.0%"/>'
		);
		expect(xml).toContain(
			'<TimeCalc name="Amount R3" type="rolling" measure="amount" window="3" function="avg"/>'
		);
		// The incomplete row (blank measure) is not emitted.
		expect(xml).not.toContain('name="Incomplete"');
	});

	it('emits the dimension table PK in PhysicalSchema + Dimension key', () => {
		const xml = exportToMondrianXml(cubeDoc());
		expect(xml).toContain('<Table name="customer" schema="public" keyColumn="id" />');
		// The dim carries an explicit `id` attribute bound to the PK column, so
		// the key resolves to that attribute (no synthetic "Customer Key").
		expect(xml).toContain('<Dimension name="Customer" table="customer" key="id">');
	});

	it('falls back to the single-fact export when the doc has no cubes', () => {
		const doc = cubeDoc();
		doc.cubes = [];
		const xml = exportToMondrianXml(doc);
		// Fallback names the single MeasureGroup after the cube (title-cased
		// fact) and emits the flat state.measures list.
		expect(xml).toContain('<MeasureGroup name="Sales Fact" table="sales_fact">');
		expect(xml).toContain('<Measure name="Sales" column="amount" aggregator="sum"');
	});
});

describe('exportToMondrianYaml', () => {
	it('derives M4 YAML from the same state as the XML export', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [{ name: 'customer_id', sqlType: 'int' }],
					position: { x: 0, y: 0 },
					groupId: null
				},
				{
					id: 'c',
					schema: 'public',
					name: 'customer',
					role: 'dimension',
					columns: [
						{ name: 'id', sqlType: 'int' },
						{ name: 'country', sqlType: 'text' }
					],
					position: { x: 100, y: 0 },
					groupId: null
				}
			],
			dimensions: [
				{
					id: 'd1',
					name: 'Customer',
					foreignKey: 'customer_id',
					primaryKey: 'id',
					primaryKeyTableId: 'c',
					hierarchies: [
						{
							id: 'h1',
							name: 'Geo',
							hasAll: true,
							levels: [
								{
									id: 'l1',
									name: 'Country',
									tableId: 'c',
									columnName: 'country',
									type: 'String'
								}
							]
						}
					],
					dimensionType: 'Standard'
				}
			],
			measures: [
				{
					id: 'm',
					name: 'Sales',
					aggregator: 'sum',
					tableId: 'f',
					columnName: 'amount'
				}
			]
		};
		const parsed = parse(exportToMondrianYaml(doc));
		expect(parsed.schema.metamodel_version).toBe('4.0');
		expect(parsed.shared_dimensions.Customer.key).toBe('Customer Key');
		expect(parsed.cubes['Sales Fact'].measure_groups[0].dimension_links).toEqual([
			{
				type: 'foreign_key',
				dimension: 'Customer',
				foreign_key_column: 'customer_id'
			}
		]);
	});

	it('never emits a Mondrian 3 marker', () => {
		const doc: SchemaCanvasState = {
			...baseDoc(),
			tables: [
				{
					id: 'f',
					schema: 'public',
					name: 'sales_fact',
					role: 'fact',
					columns: [{ name: 'amount', sqlType: 'numeric' }],
					position: { x: 0, y: 0 },
					groupId: null
				}
			]
		};
		const yaml = exportToMondrianYaml(doc);
		expect(yaml).not.toContain('foreignKey');
		expect(yaml).not.toContain('primaryKey');
	});
});
