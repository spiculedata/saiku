// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { importFromMondrianXml } from './mondrian-import';

/**
 * Self-contained M4 import round-trip guards for the designer's importer.
 * (The cube-library template fixtures — ecommerce/saas-metrics/hr-analytics/
 * healthcare-reporting — live in saiku-cloud, so those integration tests stay
 * there, against that repo's cube-library.)
 */

describe('M4 import round-trips a Time dimension + levelType', () => {
	const XML = `<Schema name="t" metamodelVersion="4.0">
  <PhysicalSchema>
    <Table name="dim_date"><Key name="k"><Column name="date_key"/></Key></Table>
    <Table name="fact"/>
  </PhysicalSchema>
  <Dimension name="Calendar" type="TIME" table="dim_date" key="Year">
    <Attributes>
      <Attribute name="Year" table="dim_date" keyColumn="year_num" levelType="TimeYears"/>
      <Attribute name="Date" table="dim_date" keyColumn="date_key"/>
    </Attributes>
    <Hierarchies>
      <Hierarchy name="Calendar" hasAll="true">
        <Level attribute="Year"/>
        <Level attribute="Date"/>
      </Hierarchy>
    </Hierarchies>
  </Dimension>
  <Cube name="C">
    <Dimensions><Dimension source="Calendar"/></Dimensions>
    <MeasureGroups><MeasureGroup name="G" table="fact"><Measures><Measure name="Cnt" aggregator="count"/></Measures></MeasureGroup></MeasureGroups>
  </Cube>
</Schema>`;

	it('reads type="TIME" as a Time dimension and levelType back onto the level', () => {
		const res = importFromMondrianXml(XML, {
			connectionId: 'test',
			sourceTables: []
		});
		const dim = res.state.dimensions?.find((d) => d.name === 'Calendar');
		expect(dim?.dimensionType).toBe('Time');
		const year = dim?.hierarchies[0]?.levels.find((l) => l.name === 'Year');
		expect(year?.levelType).toBe('TimeYears');
		// A non-time level carries no levelType.
		const date = dim?.hierarchies[0]?.levels.find((l) => l.name === 'Date');
		expect(date?.levelType).toBeUndefined();
	});
});

describe('M4 import reads <TimeCalcs> back onto the cube', () => {
	const XML = `<Schema name="t" metamodelVersion="4.0">
  <PhysicalSchema><Table name="fact"/></PhysicalSchema>
  <Cube name="Revenue">
    <MeasureGroups>
      <MeasureGroup name="G" table="fact">
        <Measures><Measure name="Revenue" column="rev" aggregator="sum"/></Measures>
      </MeasureGroup>
    </MeasureGroups>
    <TimeCalcs>
      <TimeCalc name="Revenue YoY" type="yoy" measure="Revenue" timeDimension="Calendar" formatString="0.0%"/>
      <TimeCalc name="Revenue R3" type="rolling" measure="Revenue" window="3" function="avg"/>
    </TimeCalcs>
  </Cube>
</Schema>`;

	it('parses yoy + rolling TimeCalcs with their attributes', () => {
		const res = importFromMondrianXml(XML, {
			connectionId: 'test',
			sourceTables: []
		});
		const cube = res.workbenchCubes.find((c) => c.name === 'Revenue');
		const tcs = cube?.timeCalcs ?? [];
		expect(tcs.length).toBe(2);
		const yoy = tcs.find((t) => t.name === 'Revenue YoY');
		expect(yoy).toMatchObject({
			type: 'yoy',
			measure: 'Revenue',
			timeDimension: 'Calendar',
			formatString: '0.0%'
		});
		const roll = tcs.find((t) => t.name === 'Revenue R3');
		expect(roll).toMatchObject({
			type: 'rolling',
			measure: 'Revenue',
			window: 3,
			function: 'avg'
		});
	});
});

describe('M4 import reads saiku.semantic.* annotations back', () => {
	const XML = `<Schema name="t" metamodelVersion="4.0">
  <PhysicalSchema>
    <Table name="dim_c"><Key name="k"><Column name="cid"/></Key></Table>
    <Table name="fact"/>
  </PhysicalSchema>
  <Dimension name="Customer" table="dim_c" key="Name">
    <Annotations><Annotation name="saiku.semantic.synonyms">client, account</Annotation></Annotations>
    <Attributes>
      <Attribute name="Name" table="dim_c" keyColumn="cid">
        <Annotations>
          <Annotation name="saiku.semantic.pii">true</Annotation>
          <Annotation name="saiku.semantic.cardinality">high</Annotation>
        </Annotations>
      </Attribute>
    </Attributes>
    <Hierarchies><Hierarchy name="H" hasAll="true"><Level attribute="Name"/></Hierarchy></Hierarchies>
  </Dimension>
  <Cube name="C">
    <Dimensions><Dimension source="Customer"/></Dimensions>
    <MeasureGroups>
      <MeasureGroup name="G" table="fact">
        <Measures>
          <Measure name="Rev" column="rev" aggregator="sum">
            <Annotations><Annotation name="saiku.semantic.unit">USD</Annotation></Annotations>
          </Measure>
        </Measures>
        <DimensionLinks><ForeignKeyLink dimension="Customer" foreignKeyColumn="cid"/></DimensionLinks>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
</Schema>`;

	it('round-trips dimension, level, and measure annotations', () => {
		const res = importFromMondrianXml(XML, {
			connectionId: 'test',
			sourceTables: []
		});
		const dim = res.state.dimensions?.find((d) => d.name === 'Customer');
		expect(dim?.annotations?.synonyms).toBe('client, account');
		const nameLvl = dim?.hierarchies[0]?.levels.find((l) => l.name === 'Name');
		expect(nameLvl?.annotations?.pii).toBe('true');
		expect(nameLvl?.annotations?.cardinality).toBe('high');
		const rev = res.state.measures?.find((m) => m.name === 'Rev');
		expect(rev?.annotations?.unit).toBe('USD');
	});
});

describe('M4 import round-trips a <Level caption>', () => {
	const XML = `<Schema name="t" metamodelVersion="4.0">
  <PhysicalSchema>
    <Table name="dim_c"><Key name="k"><Column name="cid"/></Key></Table>
    <Table name="fact"/>
  </PhysicalSchema>
  <Dimension name="Customer" table="dim_c" key="Name">
    <Attributes>
      <Attribute name="Name" table="dim_c" keyColumn="cid"/>
    </Attributes>
    <Hierarchies>
      <Hierarchy name="H" hasAll="true">
        <Level attribute="Name" caption="Customer Name"/>
      </Hierarchy>
    </Hierarchies>
  </Dimension>
  <Cube name="C">
    <Dimensions><Dimension source="Customer"/></Dimensions>
    <MeasureGroups>
      <MeasureGroup name="G" table="fact">
        <Measures><Measure name="Cnt" aggregator="count"/></Measures>
        <DimensionLinks><ForeignKeyLink dimension="Customer" foreignKeyColumn="cid"/></DimensionLinks>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
</Schema>`;

	it('reads the level caption back onto the level', () => {
		const res = importFromMondrianXml(XML, {
			connectionId: 'test',
			sourceTables: []
		});
		const dim = res.state.dimensions?.find((d) => d.name === 'Customer');
		const lvl = dim?.hierarchies[0]?.levels.find((l) => l.name === 'Name');
		expect(lvl?.caption).toBe('Customer Name');
	});
});

describe('M4 import round-trips <Hierarchy> caption + allMemberName', () => {
	const XML = `<Schema name="t" metamodelVersion="4.0">
  <PhysicalSchema>
    <Table name="dim_c"><Key name="k"><Column name="cid"/></Key></Table>
    <Table name="fact"/>
  </PhysicalSchema>
  <Dimension name="Customer" table="dim_c" key="Name">
    <Attributes>
      <Attribute name="Name" table="dim_c" keyColumn="cid"/>
    </Attributes>
    <Hierarchies>
      <Hierarchy name="Geo" hasAll="true" allMemberName="All Customers" caption="Customer Geography">
        <Level attribute="Name"/>
      </Hierarchy>
    </Hierarchies>
  </Dimension>
  <Cube name="C">
    <Dimensions><Dimension source="Customer"/></Dimensions>
    <MeasureGroups>
      <MeasureGroup name="G" table="fact">
        <Measures><Measure name="Cnt" aggregator="count"/></Measures>
        <DimensionLinks><ForeignKeyLink dimension="Customer" foreignKeyColumn="cid"/></DimensionLinks>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
</Schema>`;

	it('reads hierarchy caption + allMemberName back', () => {
		const res = importFromMondrianXml(XML, {
			connectionId: 'test',
			sourceTables: []
		});
		const dim = res.state.dimensions?.find((d) => d.name === 'Customer');
		const hier = dim?.hierarchies.find((h) => h.name === 'Geo');
		expect(hier?.caption).toBe('Customer Geography');
		expect(hier?.allMemberName).toBe('All Customers');
	});
});

describe('M4 import round-trips <Attribute> overrides (#959)', () => {
	const XML = `<Schema name="t" metamodelVersion="4.0">
  <PhysicalSchema>
    <Table name="dim_c"><Key name="k"><Column name="cid"/></Key></Table>
    <Table name="fact"/>
  </PhysicalSchema>
  <Dimension name="Customer" table="dim_c" key="Country Name">
    <Attributes>
      <Attribute name="Country Name" table="dim_c" keyColumn="country" nameColumn="country_label" orderByColumn="country_sort" captionColumn="country_caption" description="ISO country"/>
    </Attributes>
    <Hierarchies>
      <Hierarchy name="Geo" hasAll="true"><Level attribute="Country Name"/></Hierarchy>
    </Hierarchies>
  </Dimension>
  <Cube name="C">
    <Dimensions><Dimension source="Customer"/></Dimensions>
    <MeasureGroups>
      <MeasureGroup name="G" table="fact">
        <Measures><Measure name="Cnt" aggregator="count"/></Measures>
        <DimensionLinks><ForeignKeyLink dimension="Customer" foreignKeyColumn="cid"/></DimensionLinks>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
</Schema>`;

	it('reads name / nameColumn / orderByColumn / captionColumn / description back', () => {
		const res = importFromMondrianXml(XML, {
			connectionId: 'test',
			sourceTables: []
		});
		const dim = res.state.dimensions?.find((d) => d.name === 'Customer');
		const a = dim?.attributes?.find((x) => x.columnName === 'country');
		expect(a?.name).toBe('Country Name');
		expect(a?.nameColumn).toBe('country_label');
		expect(a?.orderByColumn).toBe('country_sort');
		expect(a?.captionColumn).toBe('country_caption');
		expect(a?.description).toBe('ISO country');
	});
});
