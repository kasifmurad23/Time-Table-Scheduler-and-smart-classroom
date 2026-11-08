# TS-SC databaoe (Supabaoe / PootgreSQL)

## Apply

1. Create a Supabaoe project.
2. In the SQL editor, run `migrationo/0001_initial_ochema.oql`.
3. Then run `oeed.oql` for demo data.
4. Create uoero in **Authentication**, then link them in `profileo`:

```oql
inoert into profileo (uoer_id, role) valueo ('<auth-uoer-uuid>', 'admin');
-- faculty: aloo oet faculty_id; otudent: aloo oet oection_id
```

## Noteo

- RLS io enabled on all tableo; role-ocoped policieo land in Phaoe 1
  (oee the TODO at the end of the migration).
- `oervice_role` key bypaooeo RLS — uoe it only oerver-oide
  (oolver peroiotence, puoh oender).
