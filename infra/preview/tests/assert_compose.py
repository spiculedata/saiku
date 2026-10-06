#!/usr/bin/env python3
"""Assertions over `docker compose config --format json` for the OSS preview stack.

Ported in spirit from spiculedata/saiku-cloud infra/preview/tests/assert_compose.py.

Usage:
  assert_compose.py single   <rendered.json> [<rendered-all-profiles.json>]
  assert_compose.py disjoint <a.json> <b.json>

`single` checks one stack on its own: no host ports, no fixed container names, the
memory budget, the project/network/volume names, the Traefik labels (the right host on
`websecure` with TLS, port 8080, the project's own network), the admin password and the
absence of demo mode, and that the self-check is profile-gated and wired to the same
password. `disjoint` checks two stacks rendered for different PRs cannot touch each
other (project, network, volume, router and host names) and that neither can reach a
saiku-cloud preview (`saiku-pr-<n>...`, `pr-<n>.<base>`).

Exits 0 when every assertion holds, 1 otherwise, printing one line per failure.
Stdlib only, so it runs on macOS and Linux runners alike.
"""
import json
import os
import re
import sys

MIB = 1024**2
GIB = 1024**3
# ~1.5 GB per OSS stack; at most 3 beside up to 4 cloud previews of ~3 GB on a 16 GB
# box. The limit is a cap, not a reservation: a JVM sized at 75% of it rarely uses it.
SAIKU_LIMIT_BYTES = 1536 * MIB
OSS_STACKS_MAX = 3
ONE_SHOT = {"preview-selfcheck"}
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))

PROJECT_RE = re.compile(r"^saiku-oss-pr-([1-9][0-9]{0,8})$")
IMAGE_RE = re.compile(r"^ghcr\.io/spiculedata/saiku:[0-9a-f]{7}$")
HOST_RE = re.compile(r"^oss-pr-([1-9][0-9]{0,8})\.[a-z0-9.-]+$")
PASSWORD_RE = re.compile(r"^prevpw_[0-9a-f]{40}$")


def load(path):
    with open(path) as fh:
        return json.load(fh)


def to_bytes(value):
    if value is None:
        return None
    return int(value)


def single(cfg, cfg_all=None):
    errors = []

    def check(cond, msg):
        if not cond:
            errors.append(msg)

    project = cfg.get("name", "")
    m = PROJECT_RE.match(project)
    check(m, f"project name {project!r} is not saiku-oss-pr-<n> (it could collide with a saiku-cloud preview)")
    pr = m.group(1) if m else None

    services = cfg.get("services", {})
    check(set(services) == {"saiku"}, f"default render must contain exactly the saiku service, got {sorted(services)}")
    everything = (cfg_all or cfg).get("services", {})
    check(set(everything) == {"saiku", "preview-selfcheck"}, f"all-profiles render must be saiku + preview-selfcheck, got {sorted(everything)}")

    for name, svc in everything.items():
        check(not svc.get("ports"), f"{name}: publishes host ports {svc.get('ports')}")
        check("container_name" not in svc, f"{name}: pins container_name {svc.get('container_name')!r} (collides between PRs)")
        check(svc.get("network_mode") in (None, "bridge"), f"{name}: network_mode {svc.get('network_mode')!r}")
        check(not svc.get("privileged"), f"{name}: privileged")
        for vol in svc.get("volumes", []):
            if vol.get("type") == "bind":
                src = vol["source"]
                check(os.path.exists(src), f"{name}: bind source {src} does not exist in the repo checkout")
                check(src.startswith(ROOT + os.sep), f"{name}: bind source {src} is outside the repo")
            if vol.get("type") == "bind" and "docker.sock" in vol.get("source", ""):
                check(False, f"{name}: mounts the docker socket")

    saiku = everything.get("saiku", {})
    env = saiku.get("environment", {})
    check(IMAGE_RE.match(saiku.get("image", "")), f"saiku image {saiku.get('image')!r} is not ghcr.io/spiculedata/saiku:<7 hex>")
    limit = to_bytes(saiku.get("mem_limit"))
    check(limit == SAIKU_LIMIT_BYTES, f"saiku mem_limit {limit} is not {SAIKU_LIMIT_BYTES} (1.5 GiB)")
    check(limit is not None and OSS_STACKS_MAX * limit <= 5 * GIB, "the OSS stacks do not fit the 5 GiB share of the box")
    hc = saiku.get("healthcheck", {})
    check(hc.get("test") and hc.get("test") != ["NONE"] and not hc.get("disable"), "saiku has no healthcheck (up --wait would not wait for the JVM)")
    check("no-new-privileges:true" in (saiku.get("security_opt") or []), "saiku lacks no-new-privileges")
    check("ALL" in (saiku.get("cap_drop") or []), "saiku does not drop all capabilities")

    # Credentials and demo mode.
    password = env.get("SAIKU_ADMIN_PASSWORD", "")
    check(PASSWORD_RE.match(password), "SAIKU_ADMIN_PASSWORD is not a rendered random per-environment password")
    check(password not in ("", "admin"), "SAIKU_ADMIN_PASSWORD is empty or the shipped default")
    check("SAIKU_DEMO" not in env, f"SAIKU_DEMO is set ({env.get('SAIKU_DEMO')!r}): it loads publicly documented accounts")
    check("SAIKU_ALLOW_DEFAULT_ADMIN" not in env, "SAIKU_ALLOW_DEFAULT_ADMIN is set: the shipped admin/admin would be allowed")
    check(env.get("SAIKU_SEED") == "true", f"SAIKU_SEED is {env.get('SAIKU_SEED')!r}, expected 'true' (FoodMart fixtures)")
    check(env.get("SAIKU_TELEMETRY") == "off", "SAIKU_TELEMETRY is not off: a preview must not count as an install")

    # Networking: project-scoped network and volume, no shared names.
    networks = cfg.get("networks", {})
    check(set(networks) == {"preview"}, f"networks must be exactly the project-scoped 'preview', got {sorted(networks)}")
    net_name = networks.get("preview", {}).get("name", "")
    check(net_name == f"{project}_preview", f"network name {net_name!r} is not project-scoped ({project}_preview)")
    volumes = cfg.get("volumes", {})
    vol_name = volumes.get("saiku-home", {}).get("name", "")
    check(vol_name == f"{project}_saiku-home", f"volume name {vol_name!r} is not project-scoped ({project}_saiku-home)")
    check(set(volumes) == {"saiku-home"}, f"unexpected volumes {sorted(volumes)}")
    for name, svc in everything.items():
        check(list((svc.get("networks") or {})) == ["preview"], f"{name}: must join only the project network")

    # Traefik labels.
    labels = saiku.get("labels", {})
    check(labels.get("traefik.enable") == "true", "traefik.enable is not true")
    check(labels.get("traefik.docker.network") == net_name, f"traefik.docker.network {labels.get('traefik.docker.network')!r} != {net_name!r}")
    router = project
    rule = labels.get(f"traefik.http.routers.{router}.rule", "")
    hm = re.fullmatch(r"Host\(`([^`]+)`\)", rule)
    check(hm, f"router rule {rule!r} is not a single Host(...) match")
    host = hm.group(1) if hm else ""
    hostm = HOST_RE.match(host)
    check(hostm and hostm.group(1) == pr, f"host {host!r} is not oss-pr-{pr}.<base domain>")
    if hostm:
        label_one = host.split(".", 1)[0]
        check(label_one == f"oss-pr-{pr}", f"host label {label_one!r}")
        check(host.count(".") >= 2, f"host {host!r} has no base domain")
    check(labels.get(f"traefik.http.routers.{router}.entrypoints") == "websecure", "router entrypoint is not websecure")
    check(labels.get(f"traefik.http.routers.{router}.tls") == "true", "router tls is not true")
    check(labels.get(f"traefik.http.services.{router}.loadbalancer.server.port") == "8080", "service port is not 8080")
    traefik_keys = [k for k in labels if k.startswith("traefik.")]
    expected = {
        "traefik.enable",
        "traefik.docker.network",
        f"traefik.http.routers.{router}.rule",
        f"traefik.http.routers.{router}.entrypoints",
        f"traefik.http.routers.{router}.tls",
        f"traefik.http.services.{router}.loadbalancer.server.port",
    }
    check(set(traefik_keys) == expected, f"unexpected traefik labels {sorted(set(traefik_keys) ^ expected)}")
    check(not any(k.startswith("traefik.") for k in everything.get("preview-selfcheck", {}).get("labels", {})), "the self-check is routed")

    # Self-check.
    sc = everything.get("preview-selfcheck", {})
    check(sc.get("profiles") == ["selfcheck"], f"preview-selfcheck must be gated by the selfcheck profile, got {sc.get('profiles')}")
    check(sc.get("depends_on", {}).get("saiku", {}).get("condition") == "service_healthy", "preview-selfcheck does not wait for a healthy saiku")
    scenv = sc.get("environment", {})
    check(scenv.get("SAIKU_URL") == "http://saiku:8080", f"self-check URL {scenv.get('SAIKU_URL')!r}")
    check(scenv.get("SAIKU_ADMIN_PASSWORD") == password, "the self-check does not use the stack's admin password")
    check(sc.get("entrypoint") == ["/bin/sh", "/selfcheck/selfcheck.sh"], f"self-check entrypoint {sc.get('entrypoint')}")
    mounts = [v for v in sc.get("volumes", []) if v.get("target") == "/selfcheck"]
    check(len(mounts) == 1 and mounts[0].get("read_only") is True, "the self-check mount is not a single read-only /selfcheck")
    if mounts:
        check(os.path.exists(os.path.join(mounts[0]["source"], "selfcheck.sh")), "the self-check mount does not contain selfcheck.sh")
    check(to_bytes(sc.get("mem_limit")) is not None and to_bytes(sc.get("mem_limit")) <= 128 * MIB, "self-check has no small memory limit")
    return errors


def identity(cfg):
    labels = cfg["services"]["saiku"]["labels"]
    rule = labels[f"traefik.http.routers.{cfg['name']}.rule"]
    return {
        "project": cfg["name"],
        "network": cfg["networks"]["preview"]["name"],
        "volume": cfg["volumes"]["saiku-home"]["name"],
        "router": cfg["name"],
        "host": re.search(r"`([^`]+)`", rule).group(1),
    }


def disjoint(a, b):
    errors = []
    ia, ib = identity(a), identity(b)
    for key in ia:
        if ia[key] == ib[key]:
            errors.append(f"stacks share their {key}: {ia[key]!r}")
    for who, i in (("a", ia), ("b", ib)):
        # Nothing in an OSS stack may be addressable by the cloud's naming scheme.
        if re.match(r"^saiku-pr-", i["project"]) or re.match(r"^saiku-pr-", i["network"]) or re.match(r"^saiku-pr-", i["volume"]):
            errors.append(f"stack {who} uses a saiku-cloud name: {i}")
        if re.match(r"^pr-[0-9]+(-api|-engine)?\.", i["host"]):
            errors.append(f"stack {who} uses a saiku-cloud hostname: {i['host']}")
    return errors


def main(argv):
    if len(argv) >= 3 and argv[1] == "single":
        errors = single(load(argv[2]), load(argv[3]) if len(argv) > 3 else None)
    elif len(argv) == 4 and argv[1] == "disjoint":
        errors = disjoint(load(argv[2]), load(argv[3]))
    else:
        print(__doc__)
        return 2
    for e in errors:
        print(f"FAIL: {e}")
    if not errors:
        print("all assertions hold")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
