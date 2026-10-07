// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { importFromMondrianXml, isMondrian3Xml, AmbiguousSchemaError } from './mondrian-import';
import type { SourceTableCandidate } from './types.js';

/**
 * Coverage for the dispatcher (`importFromMondrianXml`), the M3-detection
 * helper (`isMondrian3Xml`), the PhysicalSchema-only parser, and the
 * Mondrian-3 cube parser — none of which had any test before this file.
 * The sibling `mondrian-import.m4-templates.test.ts` only exercises narrow
 * M4 feature round-trips (TimeCalc, annotations, captions); it never
 * touches the dispatch logic, M3 parsing, or the bare-PhysicalSchema path.
 */

describe('importFromMondrianXml — dispatch', () => {
	it('routes a bare <PhysicalSchema> root to the physical-only parser', () => {
		const XML = `<PhysicalSchema>
			<Table name="customer"><Key name="k"><Column name="id"/></Key></Table>
		</PhysicalSchema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.tables.map((t) => t.name)).toEqual(['customer']);
		expect(res.workbenchCubes).toEqual([]);
	});

	it('routes a legacy Mondrian-3 schema (no metamodelVersion, no MeasureGroups) to the M3 parser', () => {
		const XML = `<Schema name="FoodMart">
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<Measure name="Count" column="unit_sales" aggregator="sum"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.tables.map((t) => t.name)).toEqual(['sales_fact']);
		expect(res.workbenchCubes[0]?.name).toBe('Sales');
	});

	it('routes an M4 schema with metamodelVersion="4.0" to the M4 parser', () => {
		const XML = `<Schema name="t" metamodelVersion="4.0">
			<PhysicalSchema><Table name="fact"/></PhysicalSchema>
			<Cube name="C">
				<MeasureGroups><MeasureGroup name="G" table="fact">
					<Measures><Measure name="Cnt" aggregator="count"/></Measures>
				</MeasureGroup></MeasureGroups>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.tables.map((t) => t.name)).toEqual(['fact']);
		expect(res.workbenchCubes[0]?.name).toBe('C');
	});

	it('routes an M4 schema with no metamodelVersion attr but a <MeasureGroups> child to the M4 parser (regression c6c3bda3)', () => {
		// Previously the dispatcher fell through to parsePhysicalSchemaOnly for
		// every M4 schema and silently skipped the cube walker — this is the
		// exact bug the module's own top comment calls out as a regression.
		const XML = `<Schema name="t">
			<PhysicalSchema><Table name="fact"/></PhysicalSchema>
			<Cube name="C">
				<MeasureGroups><MeasureGroup name="G" table="fact">
					<Measures><Measure name="Cnt" aggregator="count"/></Measures>
				</MeasureGroup></MeasureGroups>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.tables.find((t) => t.role === 'fact')?.name).toBe('fact');
	});

	it('throws a clear error on malformed XML', () => {
		expect(() => importFromMondrianXml('<Schema><Cube', { connectionId: 'c' })).toThrow(
			/XML parse error/
		);
	});

	it('throws when the root is neither <Schema> nor <PhysicalSchema>', () => {
		expect(() => importFromMondrianXml('<Foo/>', { connectionId: 'c' })).toThrow(
			/Expected a <Schema> or <PhysicalSchema>/
		);
	});
});

describe('isMondrian3Xml', () => {
	it('is true for a legacy schema with no M4 markers', () => {
		const XML = `<Schema name="FoodMart"><Cube name="Sales"><Table name="f"/></Cube></Schema>`;
		expect(isMondrian3Xml(XML)).toBe(true);
	});

	it('is false for metamodelVersion="4.0"', () => {
		const XML = `<Schema name="t" metamodelVersion="4.0"><Cube name="C"/></Schema>`;
		expect(isMondrian3Xml(XML)).toBe(false);
	});

	it('is false for any 4.x metamodelVersion, not just "4.0" (e.g. engine-upgraded "4.8")', () => {
		const XML = `<Schema name="t" metamodelVersion="4.8"><Cube name="C"/></Schema>`;
		expect(isMondrian3Xml(XML)).toBe(false);
	});

	it('is false when <MeasureGroups> is present even without a metamodelVersion attribute', () => {
		const XML = `<Schema name="t"><Cube name="C"><MeasureGroups/></Cube></Schema>`;
		expect(isMondrian3Xml(XML)).toBe(false);
	});

	it('is false for a bare <PhysicalSchema> paste', () => {
		expect(isMondrian3Xml('<PhysicalSchema><Table name="t"/></PhysicalSchema>')).toBe(false);
	});

	it('is false for malformed XML', () => {
		expect(isMondrian3Xml('<Schema><Cube')).toBe(false);
	});

	it('is false when there is no <Schema> element at all', () => {
		expect(isMondrian3Xml('<Foo/>')).toBe(false);
	});
});

describe('importFromMondrianXml — bare <PhysicalSchema> parser', () => {
	it('throws when the PhysicalSchema has no <Table> elements', () => {
		expect(() => importFromMondrianXml('<PhysicalSchema/>', { connectionId: 'c' })).toThrow(
			/no <Table> elements/
		);
	});

	it('parses tables and Links into joins, sourcing the source column from the Key and the target column from the ForeignKey', () => {
		const XML = `<PhysicalSchema>
			<Table name="customer"><Key name="k"><Column name="customer_id"/></Key></Table>
			<Table name="sales"/>
			<Link source="customer" target="sales">
				<ForeignKey><Column name="customer_id"/></ForeignKey>
			</Link>
		</PhysicalSchema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.joins).toHaveLength(1);
		const join = res.state.joins[0];
		expect(join.sourceColumnName).toBe('customer_id');
		expect(join.targetColumnName).toBe('customer_id');
		expect(join.origin).toBe('physical');
	});

	it('warns and skips a Link missing source/target attributes', () => {
		const XML = `<PhysicalSchema>
			<Table name="a"/>
			<Link target="a"><ForeignKey><Column name="x"/></ForeignKey></Link>
		</PhysicalSchema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.joins).toEqual([]);
		expect(res.warnings.some((w) => w.includes('missing source/target'))).toBe(true);
	});

	it('warns and skips a Link with no <ForeignKey><Column>', () => {
		const XML = `<PhysicalSchema>
			<Table name="a"/><Table name="b"/>
			<Link source="a" target="b"/>
		</PhysicalSchema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.joins).toEqual([]);
		expect(res.warnings.some((w) => w.includes('no <ForeignKey><Column>'))).toBe(true);
	});

	it('warns and skips a Link referencing an undefined table', () => {
		const XML = `<PhysicalSchema>
			<Table name="a"/>
			<Link source="a" target="ghost"><ForeignKey><Column name="x"/></ForeignKey></Link>
		</PhysicalSchema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.joins).toEqual([]);
		expect(res.warnings.some((w) => w.includes('references an undefined table'))).toBe(true);
	});

	it('enriches a table from the live source-table catalog and keeps the catalog schema over a mismatched XML schema', () => {
		const sourceTables: SourceTableCandidate[] = [
			{
				schema: 'public',
				name: 'customer',
				columns: [{ name: 'id', sqlType: 'INTEGER' }],
				onCanvas: false
			}
		];
		const XML = `<PhysicalSchema>
			<Table name="customer" schema="other"/>
		</PhysicalSchema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c', sourceTables });
		const table = res.state.tables[0];
		expect(table.schema).toBe('public');
		expect(table.columns).toEqual([{ name: 'id', sqlType: 'INTEGER' }]);
		expect(res.warnings.some((w) => w.includes('matched "public" in the catalog'))).toBe(true);
	});

	it("warns when a table isn't found in the catalog and falls back to the XML's own columns", () => {
		const XML = `<PhysicalSchema>
			<Table name="orphan"><Key name="k"><Column name="id"/></Key></Table>
		</PhysicalSchema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c', sourceTables: [] });
		expect(
			res.warnings.some((w) => w.includes("wasn't found in the active connection's catalog"))
		).toBe(true);
	});

	it('throws AmbiguousSchemaError when an unqualified table name exists in more than one catalog schema', () => {
		const sourceTables: SourceTableCandidate[] = [
			{ schema: 'a', name: 'customer', columns: [], onCanvas: false },
			{ schema: 'b', name: 'customer', columns: [], onCanvas: false }
		];
		const XML = `<PhysicalSchema><Table name="customer"/></PhysicalSchema>`;
		try {
			importFromMondrianXml(XML, { connectionId: 'c', sourceTables });
			throw new Error('expected AmbiguousSchemaError');
		} catch (e) {
			expect(e).toBeInstanceOf(AmbiguousSchemaError);
			expect((e as AmbiguousSchemaError).ambiguities).toEqual([
				{ tableName: 'customer', candidateSchemas: ['a', 'b'] }
			]);
		}
	});

	it('resolves an ambiguity via schemaOverrides instead of throwing', () => {
		const sourceTables: SourceTableCandidate[] = [
			{
				schema: 'a',
				name: 'customer',
				columns: [{ name: 'id', sqlType: 'INTEGER' }],
				onCanvas: false
			},
			{
				schema: 'b',
				name: 'customer',
				columns: [{ name: 'id', sqlType: 'INTEGER' }],
				onCanvas: false
			}
		];
		const XML = `<PhysicalSchema><Table name="customer"/></PhysicalSchema>`;
		const res = importFromMondrianXml(XML, {
			connectionId: 'c',
			sourceTables,
			schemaOverrides: { customer: 'b' }
		});
		expect(res.state.tables[0].schema).toBe('b');
	});
});

describe('importFromMondrianXml — Mondrian 3 cube parser', () => {
	it('throws when the schema has no <Cube>', () => {
		expect(() => importFromMondrianXml('<Schema name="t"/>', { connectionId: 'c' })).toThrow(
			/Schema has no <Cube>/
		);
	});

	it('warns and skips a Cube with no fact <Table>', () => {
		const XML = `<Schema name="t"><Cube name="Empty"/></Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.tables).toEqual([]);
		expect(res.warnings.some((w) => w.includes('has no <Table> (fact)'))).toBe(true);
	});

	it('builds a fact + dimension table pair and a join from an inline <Dimension><Hierarchy>', () => {
		const XML = `<Schema name="t">
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<Dimension name="Customer" foreignKey="customer_id">
					<Hierarchy hasAll="true" primaryKey="id">
						<Table name="customer"/>
						<Level name="Name" column="name"/>
					</Hierarchy>
				</Dimension>
				<Measure name="Revenue" column="amount" aggregator="sum"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		const names = res.state.tables.map((t) => ({ name: t.name, role: t.role }));
		expect(names).toEqual(
			expect.arrayContaining([
				{ name: 'sales_fact', role: 'fact' },
				{ name: 'customer', role: 'dimension' }
			])
		);
		expect(res.state.joins).toHaveLength(1);
		expect(res.state.joins[0]).toMatchObject({
			sourceColumnName: 'id',
			targetColumnName: 'customer_id'
		});
		expect(res.workbenchCubes[0]).toMatchObject({
			name: 'Sales',
			measureGroups: [expect.objectContaining({ measureColumns: ['amount'] })]
		});
	});

	it('resolves a <DimensionUsage> against a schema-level shared <Dimension>', () => {
		const XML = `<Schema name="t">
			<Dimension name="Time">
				<Hierarchy hasAll="true" primaryKey="time_id">
					<Table name="time_by_day"/>
				</Hierarchy>
			</Dimension>
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<DimensionUsage name="Time" source="Time" foreignKey="time_id"/>
				<Measure name="Revenue" column="amount" aggregator="sum"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.tables.map((t) => t.name)).toEqual(
			expect.arrayContaining(['sales_fact', 'time_by_day'])
		);
		expect(res.state.joins).toHaveLength(1);
		expect(res.state.joins[0]).toMatchObject({
			sourceColumnName: 'time_id',
			targetColumnName: 'time_id'
		});
	});

	it('warns when a <DimensionUsage> references a source with no matching schema-level <Dimension>', () => {
		const XML = `<Schema name="t">
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<DimensionUsage name="Time" source="Time" foreignKey="time_id"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.warnings.some((w) => w.includes('no schema-level <Dimension name="Time">'))).toBe(
			true
		);
	});

	it('resolves a snowflake <Hierarchy><Join> on a schema-level shared dimension into an internal join between the two dimension tables', () => {
		// Snowflake joins are only emitted for schema-level SHARED dimensions
		// (the dedicated "land ONCE" loop below the cube walk) — an inline
		// cube-level <Dimension><Hierarchy><Join> never reaches that loop.
		const XML = `<Schema name="t">
			<Dimension name="Product">
				<Hierarchy hasAll="true" primaryKey="product_id" primaryKeyTable="product">
					<Join leftKey="product_class_id" rightKey="product_class_id">
						<Table name="product"/>
						<Table name="product_class"/>
					</Join>
				</Hierarchy>
			</Dimension>
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<DimensionUsage name="Product" source="Product" foreignKey="product_id"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.tables.map((t) => t.name)).toEqual(
			expect.arrayContaining(['sales_fact', 'product', 'product_class'])
		);
		const snowflakeJoin = res.state.joins.find(
			(j) => j.sourceColumnName === 'product_class_id' && j.targetColumnName === 'product_class_id'
		);
		expect(snowflakeJoin).toBeDefined();
	});

	it('warns on a nested <Join> inside a snowflake Hierarchy (only simple 2-table snowflakes are supported)', () => {
		const XML = `<Schema name="t">
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<Dimension name="Product" foreignKey="product_id">
					<Hierarchy hasAll="true" primaryKey="product_id">
						<Join leftKey="a" rightKey="b">
							<Table name="product"/>
							<Join leftKey="c" rightKey="d"><Table name="x"/><Table name="y"/></Join>
						</Join>
					</Hierarchy>
				</Dimension>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.warnings.some((w) => w.includes('nested <Join>'))).toBe(true);
	});

	it('warns and skips a Dimension with no <Hierarchy>', () => {
		const XML = `<Schema name="t">
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<Dimension name="Bad" foreignKey="x"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.warnings.some((w) => w.includes('has no <Hierarchy>'))).toBe(true);
	});

	it('warns when a Dimension is missing foreignKey/primaryKey so the join is skipped', () => {
		const XML = `<Schema name="t">
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<Dimension name="Customer">
					<Hierarchy hasAll="true">
						<Table name="customer"/>
					</Hierarchy>
				</Dimension>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		expect(res.state.joins).toEqual([]);
		expect(res.warnings.some((w) => w.includes('missing foreignKey/primaryKey'))).toBe(true);
	});

	it('dedupes identical joins across multiple cubes sharing the same dimension', () => {
		const XML = `<Schema name="t">
			<Dimension name="Time">
				<Hierarchy hasAll="true" primaryKey="time_id"><Table name="time_by_day"/></Hierarchy>
			</Dimension>
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<DimensionUsage name="Time" source="Time" foreignKey="time_id"/>
			</Cube>
			<Cube name="Inventory">
				<Table name="inventory_fact"/>
				<DimensionUsage name="Time" source="Time" foreignKey="time_id"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c' });
		// Same dim table/column pair joining two different facts — two
		// distinct joins by target, not a single deduped one.
		expect(res.state.joins).toHaveLength(2);
		expect(res.workbenchCubes.map((c) => c.name)).toEqual(['Sales', 'Inventory']);
	});

	it('inherits the catalog schema for a bare (unqualified) fact/dimension table, FoodMart-style', () => {
		const sourceTables: SourceTableCandidate[] = [
			{
				schema: 'public',
				name: 'sales_fact',
				columns: [{ name: 'amount', sqlType: 'NUMERIC' }],
				onCanvas: false
			}
		];
		const XML = `<Schema name="t">
			<Cube name="Sales">
				<Table name="sales_fact"/>
				<Measure name="Revenue" column="amount" aggregator="sum"/>
			</Cube>
		</Schema>`;
		const res = importFromMondrianXml(XML, { connectionId: 'c', sourceTables });
		const fact = res.state.tables.find((t) => t.name === 'sales_fact');
		expect(fact?.schema).toBe('public');
		expect(fact?.columns).toEqual([{ name: 'amount', sqlType: 'NUMERIC' }]);
	});
});
