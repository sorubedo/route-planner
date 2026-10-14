# OpenStreetMap demo road data

`campus-roads.tsv` contains a small extract of OpenStreetMap geographic data.

© OpenStreetMap contributors. The data is available under the Open Database License (ODbL) 1.0:
https://opendatacommons.org/licenses/odbl/1-0/

Copyright and attribution guidance: https://www.openstreetmap.org/copyright

Source: https://api.openstreetmap.org/api/0.6/map?bbox=116.322,39.997,116.330,40.0015
Retrieved: 2026-10-13.

The test fixture is derived from `app/tests/fixtures/demo-roads.json`.
Only highway ways and their referenced nodes are retained. Contributor names, user IDs,
changeset IDs, and version/timestamp metadata are excluded. This is public map data;
the demo polygon/checkpoints are manually chosen example inputs, not university running rules.
