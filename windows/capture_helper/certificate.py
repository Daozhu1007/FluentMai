from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
import subprocess

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
from mitmproxy.certs import DEFAULT_DHPARAM


CA_COMMON_NAME = "FluentMai Local Capture CA"
CA_ORGANIZATION = "FluentMai"
MITMPROXY_BASENAME = "mitmproxy"


@dataclass(frozen=True)
class CertificateInfo:
    certificate_path: Path
    combined_key_path: Path
    thumbprint: str
    installed: bool


def ensure_ca_store(directory: str | Path) -> CertificateInfo:
    root = Path(directory).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    combined = root / f"{MITMPROXY_BASENAME}-ca.pem"
    certificate_path = root / f"{MITMPROXY_BASENAME}-ca-cert.pem"
    dhparam = root / f"{MITMPROXY_BASENAME}-dhparam.pem"

    if not _valid_existing_store(combined, certificate_path):
        _generate_store(root, combined, certificate_path, dhparam)
    elif not dhparam.is_file():
        _atomic_write(dhparam, DEFAULT_DHPARAM)

    certificate = x509.load_pem_x509_certificate(certificate_path.read_bytes())
    thumbprint = certificate.fingerprint(hashes.SHA1()).hex().upper()
    return CertificateInfo(
        certificate_path=certificate_path,
        combined_key_path=combined,
        thumbprint=thumbprint,
        installed=is_ca_installed(thumbprint),
    )


def install_ca_current_user(info: CertificateInfo) -> CertificateInfo:
    if info.installed:
        return info
    try:
        result = subprocess.run(
            ["certutil", "-user", "-addstore", "-f", "Root", str(info.certificate_path)],
            capture_output=True,
            timeout=30,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("ca_installation_timeout") from exc
    if result.returncode != 0:
        raise RuntimeError("ca_installation_failed")
    if not is_ca_installed(info.thumbprint):
        raise RuntimeError("ca_installation_verification_failed")
    return CertificateInfo(
        certificate_path=info.certificate_path,
        combined_key_path=info.combined_key_path,
        thumbprint=info.thumbprint,
        installed=True,
    )


def remove_ca_current_user(info: CertificateInfo) -> bool:
    if not is_ca_installed(info.thumbprint):
        return False
    result = subprocess.run(
        ["certutil", "-user", "-delstore", "Root", info.thumbprint],
        capture_output=True,
        timeout=30,
        check=False,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if result.returncode != 0:
        raise RuntimeError(f"Current-user CA removal failed with exit code {result.returncode}.")
    return True


def is_ca_installed(thumbprint: str) -> bool:
    if os.name != "nt":
        return False
    try:
        result = subprocess.run(
            ["certutil", "-user", "-verifystore", "Root", thumbprint],
            capture_output=True,
            timeout=15,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except subprocess.TimeoutExpired:
        return False
    return result.returncode == 0


def _valid_existing_store(combined: Path, certificate_path: Path) -> bool:
    if not combined.is_file() or not certificate_path.is_file():
        return False
    try:
        combined_bytes = combined.read_bytes()
        cert = x509.load_pem_x509_certificate(certificate_path.read_bytes())
        private_key = serialization.load_pem_private_key(combined_bytes, password=None)
        combined_cert = x509.load_pem_x509_certificate(
            combined_bytes[combined_bytes.index(b"-----BEGIN CERTIFICATE-----") :]
        )
        common_names = cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
        constraints = cert.extensions.get_extension_for_class(x509.NameConstraints).value
        permitted = {item.value for item in constraints.permitted_subtrees or [] if isinstance(item, x509.DNSName)}
        return (
            bool(common_names)
            and common_names[0].value == CA_COMMON_NAME
            and cert.fingerprint(hashes.SHA256()) == combined_cert.fingerprint(hashes.SHA256())
            and private_key.public_key().public_numbers() == cert.public_key().public_numbers()
            and "wahlap.com" in permitted
            and cert.not_valid_after_utc > datetime.now(timezone.utc) + timedelta(days=30)
        )
    except (ValueError, TypeError, x509.ExtensionNotFound):
        return False


def _generate_store(root: Path, combined: Path, certificate_path: Path, dhparam: Path) -> None:
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.now(timezone.utc)
    name = x509.Name(
        [
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, CA_ORGANIZATION),
            x509.NameAttribute(NameOID.COMMON_NAME, CA_COMMON_NAME),
        ]
    )
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(private_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(hours=1))
        .not_valid_after(now + timedelta(days=730))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=True,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=True,
                crl_sign=True,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(private_key.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(private_key.public_key()), critical=False)
        .add_extension(
            x509.NameConstraints(permitted_subtrees=[x509.DNSName("wahlap.com")], excluded_subtrees=None),
            critical=True,
        )
        .sign(private_key=private_key, algorithm=hashes.SHA256())
    )
    key_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.TraditionalOpenSSL,
        encryption_algorithm=serialization.NoEncryption(),
    )
    cert_pem = certificate.public_bytes(serialization.Encoding.PEM)

    _atomic_write(combined, key_pem + cert_pem)
    _atomic_write(certificate_path, cert_pem)
    _atomic_write(root / f"{MITMPROXY_BASENAME}-ca-cert.cer", cert_pem)
    _atomic_write(dhparam, DEFAULT_DHPARAM)


def _atomic_write(path: Path, value: bytes) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        temporary.write_bytes(value)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def safe_certificate_fingerprint(info: CertificateInfo) -> str:
    return hashlib.sha256(info.thumbprint.encode("ascii")).hexdigest()[:16]
