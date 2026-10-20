# Design

Extract prediction rendering from generic business rows. Accept only finite stored scores in [0,1], format as percentages and retain unavailable states. Only trained failure-prediction rows may display a failure probability. Confidence remains explicitly distinct from measured accuracy. Sort only the returned bounded page, preserving server total/hasNext disclosure. Device links require a device present in authorized panel data. Optional columns live in presentation, preserving compatibility with old definitions. Synthetic dashboards use fixed reproducible intervals and populated existing widget types; do not change the account default or fabricate predictions.

Expose the existing query row limit (1–50) and multiple fixed equipment bindings for business widgets. Preserve additional equipment when editing the first selection and disallow blank or duplicate selections. No backend query capability is added.

Map stored failureHorizonDays/failure_horizon_days to the existing horizonDays projection, preserving legacy horizon metadata and the safe scalar allowlist. Maintenance tables resolve deviceIds through authorized panel devices and expose optional exclusion columns; record dates use the viewer locale. Generic risk/incident tables can hide record-time columns while detailed evidence remains available.
