import { describe, it, expect } from 'vitest';
import { parse } from 'yaml';
import { mondrianXmlToYaml } from './mondrian-xml-to-yaml';

/**
 * The M4 XML the (fixed) canvas preview emits for the FoodMart-shaped schema
 * from saiku-cloud#1080 — now with physical-table keys + dimension keys.
 */
const FOODMART_M4_XML = `<?xml version="1.0" encoding="UTF-8"?>
<Schema name="Untitled" metamodelVersion="4.0">
  <PhysicalSchema>
    <Table name="sales_fact_1997" schema="public" />
    <Table name="product" schema="public" keyColumn="product_id" />
    <Table name="store" schema="public" keyColumn="store_id" />
    <Table name="time_by_day" schema="public" keyColumn="time_id" />
  </PhysicalSchema>
  <Dimension name="product" table="product" key="product Key">
    <Attributes>
      <Attribute name="product Key" keyColumn="product_id" hasHierarchy="false" />
      <Attribute name="brand_name" keyColumn="brand_name" />
    </Attributes>
  </Dimension>
  <Dimension name="store" table="store" key="store Key">
    <Attributes>
      <Attribute name="store Key" keyColumn="store_id" hasHierarchy="false" />
    </Attributes>
  </Dimension>
  <Cube name="Cube 1">
    <Dimensions>
      <Dimension source="product" />
      <Dimension source="store" />
    </Dimensions>
    <MeasureGroups>
      <MeasureGroup name="Group 1" table="sales_fact_1997">
        <Measures>
          <Measure name="store_sales" column="store_sales" aggregator="sum" />
        </Measures>
        <DimensionLinks>
          <ForeignKeyLink dimension="product" foreignKeyColumn="product_id" />
          <ForeignKeyLink dimension="store" foreignKeyColumn="store_id" />
        </DimensionLinks>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
</Schema>`;

describe('mondrianXmlToYaml', () => {
	it('produces M4 YAML matching the canonical FoodMart shape', () => {
		const yaml = mondrianXmlToYaml(FOODMART_M4_XML);
		// Round-trip through the YAML parser to assert structure (not bytes).
		// `parse` returns the library's untyped node — fine for structural asserts.
		const doc = parse(yaml);

		// schema header
		expect(doc.schema).toEqual({ name: 'Untitled', metamodel_version: '4.0' });

		// physical_schema: fact has NO key; dims carry key_column
		const tables = doc.physical_schema.tables as Array<Record<string, unknown>>;
		const fact = tables.find((t) => t.name === 'sales_fact_1997')!;
		expect(fact.key_column).toBeUndefined();
		const product = tables.find((t) => t.name === 'product')!;
		expect(product).toMatchObject({ name: 'product', schema: 'public', key_column: 'product_id' });

		// shared_dimensions: key points at the key attribute, which carries the PK column
		expect(doc.shared_dimensions.product.key).toBe('product Key');
		const keyAttr = doc.shared_dimensions.product.attributes.find(
			(a: { name: string }) => a.name === 'product Key'
		);
		expect(keyAttr).toMatchObject({ key_column: 'product_id', has_hierarchy: false });

		// cube: dimension usages + measure group + foreign_key links that RESOLVE
		// to the dimension key (product_id ↔ product Key ↔ product.product_id).
		const cube = doc.cubes['Cube 1'];
		expect(cube.dimensions).toEqual([{ source: 'product' }, { source: 'store' }]);
		const mg = cube.measure_groups[0];
		expect(mg).toMatchObject({ name: 'Group 1', table: 'sales_fact_1997' });
		expect(mg.measures[0]).toMatchObject({
			name: 'store_sales',
			column: 'store_sales',
			aggregator: 'sum'
		});
		expect(mg.dimension_links).toEqual([
			{ type: 'foreign_key', dimension: 'product', foreign_key_column: 'product_id' },
			{ type: 'foreign_key', dimension: 'store', foreign_key_column: 'store_id' }
		]);
	});

	it('maps composite <Key><Column/></Key> to a key list', () => {
		const xml = `<Schema name="S" metamodelVersion="4.0">
      <PhysicalSchema>
        <Table name="customer"><Key><Column name="a"/><Column name="b"/></Key></Table>
      </PhysicalSchema>
    </Schema>`;
		const doc = parse(mondrianXmlToYaml(xml));
		expect(doc.physical_schema.tables[0]).toEqual({ name: 'customer', key: ['a', 'b'] });
	});

	it('never throws on incomplete / non-schema XML', () => {
		expect(mondrianXmlToYaml('<not-a-schema/>')).toContain('#');
		expect(mondrianXmlToYaml('garbage <<<')).toContain('#');
	});
});
