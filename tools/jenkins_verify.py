"""Verify the approved wrapper, distribution checksum and release metadata."""
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Verified against Gradle's official release-checksums reference.
WRAPPER_SHA256 = 'e996d452d2645e70c01c11143ca2d3742734a28da2bf61f25c82bdc288c9e637'
DISTRIBUTION_SHA256 = 'a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612'


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(('#', '!')) or '=' not in line:
            continue
        key, value = line.split('=', 1)
        key = key.strip()
        if key in result:
            raise ValueError('Duplicate property: ' + key)
        result[key] = value.strip().replace('\\:', ':')
    return result


def main():
    wrapper = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
    if hashlib.sha256(wrapper.read_bytes()).hexdigest() != WRAPPER_SHA256:
        raise ValueError('Wrapper JAR differs from the approved official checksum')
    distribution = properties(ROOT / 'gradle/wrapper/gradle-wrapper.properties')
    if (distribution.get('distributionUrl') != 'https://services.gradle.org/distributions/gradle-8.8-bin.zip'
            or distribution.get('distributionSha256Sum') != DISTRIBUTION_SHA256):
        raise ValueError('Gradle distribution identity/checksum changed; verify the upgrade')
    release = properties(ROOT / 'gradle.properties')
    if not release.get('mod_version') or not release.get('minecraft_version'):
        raise ValueError('Release version metadata is missing')
    print('Approved Gradle wrapper, distribution checksum and version metadata verified')


if __name__ == '__main__':
    main()
