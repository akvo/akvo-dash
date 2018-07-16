# Setup new tenant

When creating a new tenant, make sure to be at the corresponding branch to not
get into issues. For production, make sure to be on the master branch!

Creating a new tenant consists of two things:

1. Create a new tenant
2. Add a plan to the tenant

There is two scripts (akvo.lumen.admin.add-tenant & akvo.lumen.admin-new-plan)
for this. Both of these scripts needs inputs (secrets & configs). The scripts
includes comments on what env vars are needed and where to find them. Since
there is issues running the scripts on a Mac (because of the encryption library
used) we run the scripts our docker container.

To create a new tenant
```
$ docker exec -i -t akvo-lumen_backend_1 env ENCRYPTION_KEY=... KC_URL=... KC_SECRET=... PG_HOST=... PG_DATABASE=... PG_USER=... PG_PASSWORD=... lein run -m akvo.lumen.admin.add-tenant "https://demo.akvolumen.org" "demo" daniel@akvo.org
```
To add a plan

```
docker exec -i -t akvo-lumen_backend_1 env ENCRYPTION_KEY=... KC_URL=... KC_SECRET=... PG_HOST=... PG_DATABASE=... PG_USER=... PG_PASSWORD=... lein run -m akvo.lumen.admin.new-plan demo unlimited
```

If we want to change the tenants plan it's just to add a new one and the old one
will be ended.
