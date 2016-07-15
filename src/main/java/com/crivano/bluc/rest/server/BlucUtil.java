package com.crivano.bluc.rest.server;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.util.Store;
import org.bouncycastle.util.encoders.Base64;

import bluecrystal.bcdeps.helper.DerEncoder;
import bluecrystal.domain.AppSignedInfoEx;
import bluecrystal.domain.NameValue;
import bluecrystal.domain.SignCompare;
import bluecrystal.domain.SignPolicy;
import bluecrystal.domain.SignPolicyRef;
import bluecrystal.domain.StatusConst;
import bluecrystal.service.exception.InvalidSigntureException;
import bluecrystal.service.service.ADRBService_21;
import bluecrystal.service.service.CertificateService;
import bluecrystal.service.service.CmsWithChainService;
import bluecrystal.service.service.CryptoService;
import bluecrystal.service.service.CryptoServiceImpl;
import bluecrystal.service.service.EnvelopeService;
import bluecrystal.service.service.SignVerifyService;
import bluecrystal.service.service.Validator;
import bluecrystal.service.service.ValidatorSrv;

public class BlucUtil {

	private CryptoService ccServ = null;
	private SignVerifyService verify = null;
	private CertificateService certServ = null;
	private ValidatorSrv validatorServ = null;

	public static final int NDX_SHA1 = 0;
	public static final int NDX_SHA224 = 1;
	public static final int NDX_SHA256 = 2;
	public static final int NDX_SHA384 = 3;
	public static final int NDX_SHA512 = 4;

	private static final int FALLBACK_LIMIT = 2048;

	private static CmsWithChainService serv1024;
	private static ADRBService_21 serv2048;

	public BlucUtil() {
		super();
		setCcServ(new CryptoServiceImpl());
		verify = new SignVerifyService();
		certServ = new CertificateService();
		validatorServ = new Validator();
		serv1024 = new CmsWithChainService();
		serv2048 = new ADRBService_21();
	}

	boolean certificado(byte[] certificado, CertificateResponse resp)
			throws Exception {
		X509Certificate c = loadCert(certificado);

		String cn = getCN(certificado);
		resp.setCn(cn);
		resp.setName(obterNomeExibicao(cn));
		setDetails(certificado, resp.getCertdetails());
		resp.setCpf(resp.getCertdetails().get("cpf0"));
		resp.setSubject(resp.getCertdetails().get("subject0"));

		return true;
	}

	boolean validarECompletarPacoteAssinavel(byte[] certificado, byte[] sha1,
			byte[] sha256, byte[] assinatura, boolean politica,
			Date dtAssinatura, EnvelopeResponse resp) throws Exception {
		X509Certificate c = loadCert(certificado);
		RSAPublicKey pubKey = (RSAPublicKey) c.getPublicKey();

		byte[] sign = assinatura;

		resp.setCn(obterNomeExibicao(getCN(certificado)));
		setDetails(certificado, resp.getCertdetails());

		if (pubKey.getModulus().bitLength() == FALLBACK_LIMIT) {
			resp.setEnvelope(composeEnvelopeADRB21(sign, c.getEncoded(),
					sha256, dtAssinatura));
			resp.setPolicy("AD-RB");
			resp.setPolicyversion("2.1");
			resp.setPolicyoid("2.16.76.1.7.1.1.2.1");
		} else {
			resp.setEnvelope(composeEnvelopeADRB10(sign, c.getEncoded(), sha1,
					dtAssinatura));
			resp.setPolicy("AD-RB");
			resp.setPolicyversion("1.0");
			resp.setPolicyoid("2.16.76.1.7.1.1.1");
		}
		return true;
	}

	boolean produzPacoteAssinavel(byte[] certificado, byte[] sha1,
			byte[] sha256, boolean politica, Date dtAssinatura,
			HashResponse resp) throws Exception {

		X509Certificate c = loadCert(certificado);

		resp.setCn(obterNomeExibicao(getCN(certificado)));
		setDetails(certificado, resp.getCertdetails());

		RSAPublicKey pubKey = (RSAPublicKey) c.getPublicKey();

		if (!politica) {

			resp.setHash(new String(Base64.encode(sha1)));
			resp.setPolicy("PKCS#7");
			return true;
		}

		if (pubKey.getModulus().bitLength() >= FALLBACK_LIMIT) {
			resp.setHash(hashSignedAttribADRB21(sha256, dtAssinatura,
					c.getEncoded()));
			resp.setPolicy("AD-RB");
			resp.setPolicyversion("2.1");
			resp.setPolicyoid("2.16.76.1.7.1.1.2.1");
		} else {
			resp.setHash(hashSignedAttribADRB10(sha1, dtAssinatura,
					c.getEncoded()));
			resp.setPolicy("AD-RB");
			resp.setPolicyversion("1.0");
			resp.setPolicyoid("2.16.76.1.7.1.1.1");
		}

		return true;
	}

	private String getCN(byte[] certificate) throws Exception {

		String sCert = new String(Base64.encode(certificate));
		return getCertSubjectCn(sCert);
	}

	private void setDetails(byte[] certificate, Map<String, String> map)
			throws Exception {

		String sCert = new String(Base64.encode(certificate));
		NameValue[] l = parseCertificate(sCert);

		for (NameValue nv : l) {
			map.put(nv.getName(), nv.getValue());
		}
	}

	private String hashSignedAttribADRB10(byte[] origHash, Date signingTime,
			byte[] x509) throws Exception {
		X509Certificate cert = loadCert(x509);

		byte[] ret = getCcServ().hashSignedAttribSha1(origHash, signingTime,
				cert);

		return new String(Base64.encode(ret));
	}

	private String hashSignedAttribADRB21(byte[] origHash, Date signingTime,
			byte[] x509) throws Exception {
		X509Certificate cert = loadCert(x509);

		byte[] ret = getCcServ().hashSignedAttribSha256(origHash, signingTime,
				cert);

		return new String(Base64.encode(ret));
	}

	private String extractSignature(String signB64) throws Exception {

		byte[] sign = Base64.decode(signB64);

		byte[] ret = getCcServ().extractSignature(sign);

		return new String(Base64.encode(ret));
	}

	private String composeEnvelopeADRB10(byte[] sign, byte[] x509,
			byte[] origHash, Date signingTime) throws Exception {
		X509Certificate cert = loadCert(x509);

		byte[] ret = getCcServ().composeBodySha1(sign, cert, origHash,
				signingTime);

		byte[] hashSa = getCcServ().hashSignedAttribSha1(origHash, signingTime,
				cert);

		if (!verifySign(NDX_SHA1, cert, getCcServ().calcSha1(hashSa), sign)) {
			throw new InvalidSigntureException();
		}

		return new String(Base64.encode(ret));
	}

	private String composeEnvelopeADRB21(byte[] sign, byte[] x509,
			byte[] origHash, Date signingTime) throws Exception {
		X509Certificate cert = loadCert(x509);

		byte[] ret = getCcServ().composeBodySha256(sign, cert, origHash,
				signingTime);

		byte[] hashSa = getCcServ().hashSignedAttribSha256(origHash,
				signingTime, cert);

		if (!verifySign(NDX_SHA256, cert, getCcServ().calcSha256(hashSa), sign)) {
			throw new InvalidSigntureException();
		}

		return new String(Base64.encode(ret));
	}

	private SignCompare extractSignCompare(String sign) throws Exception {

		return getCcServ().extractSignCompare(Base64.decode(sign));
	}

	private String obtemPolitica(byte[] assinatura) {
		String politica = null;
		try {
			SignCompare sc = getCcServ().extractSignCompare(assinatura);

			politica = sc.getPsOid();
		} catch (Exception e) {
		}
		return politica;
	}

	private static String obterNomeExibicao(String s) {
		s = s.split(",")[0];
		// Retira o CPF, se houver
		String[] splitted = s.split(":");
		if (splitted.length == 2)
			return splitted[0];
		return s;
	}

	private String recuperarNomePolitica(String politica) {
		switch (politica) {
		case "2.16.76.1.7.1.1.1":
			return "AD-RB v1.0";
		case "2.16.76.1.7.1.2.1":
			return "AD-RT v1.0";
		case "2.16.76.1.7.1.3.1":
			return "AD-RV v1.0";
		case "2.16.76.1.7.1.4.1":
			return "AD-RC v1.0";
		case "2.16.76.1.7.1.5.1":
			return "AD-RA v1.0";
		case "2.16.76.1.7.1.1.2.1":
			return "AD-RB v2.1";
		case "2.16.76.1.7.1.2.2.1":
			return "AD-RT v2.1";
		case "2.16.76.1.7.1.3.2.1":
			return "AD-RV v2.1";
		case "2.16.76.1.7.1.4.2.1":
			return "AD-RC v2.1";
		case "2.16.76.1.7.1.5.2.1":
			return "AD-RA v2.1";
		}
		return politica;
	}

	private boolean validateSignatureByPolicy(byte[] sign, byte[] ps)
			throws Exception {
		return ccServ.validateSignatureByPolicy(sign, ps);
	}

	private X509Certificate loadCert(byte[] certEnc)
			throws FileNotFoundException, CertificateException, IOException {
		InputStream is = new ByteArrayInputStream(certEnc);
		CertificateFactory cf = CertificateFactory.getInstance("X509");
		X509Certificate c = (X509Certificate) cf.generateCertificate(is);
		is.close();
		return c;
	}

	protected boolean verifySign(int hashId, X509Certificate cert,
			byte[] contentHash, byte[] sigBytes) throws Exception {
		return verify.verify(hashId, contentHash, sigBytes, cert);
	}

	public String extractSignerCert(String signb64) throws Exception {

		byte[] sign = Base64.decode(signb64);
		X509Certificate certEE = certServ.decodeEE(sign);
		return new String(Base64.encode(certEE.getEncoded()));
	}

	public String getCertSubject(String cert) throws Exception {
		Map<String, String> certEE = validatorServ.parseCertificateAsMap(cert);

		return certEE.get("subject0");
	}

	public String getCertSubjectCn(String cert) throws Exception {
		Map<String, String> certEE = validatorServ.parseCertificateAsMap(cert);

		String[] rdnList = certEE.get("subject0").split(",");

		for (String nextRdn : rdnList) {
			if (nextRdn.startsWith("CN")) {
				String[] cnRdn = (nextRdn.trim()).split("=");
				if (cnRdn.length == 2) {
					return cnRdn[1];
				}
			}
		}

		return null;
	}

	public NameValue[] parseCertificate(String certificate) throws Exception {
		return validatorServ.parseCertificate(certificate);
	}

	public int validateSign(byte[] assinatura, byte[] sha1, byte[] sha256,
			Date dtAssinatura, boolean verificarLCRs, ValidateResponse resp)
			throws Exception {
		String politica = obtemPolitica(assinatura);

		X509Certificate certEE = certServ.decodeEE(assinatura);

		byte[] certificate = certEE.getEncoded();
		resp.setCn(obterNomeExibicao(getCN(certificate)));
		setDetails(certificate, resp.getCertdetails());

		if (politica == null) {
			int signOk = getCcServ().validateSign(assinatura, sha1,
					dtAssinatura, verificarLCRs);
			resp.setStatus(getMessageByStatus(signOk));
			if (signOk != StatusConst.GOOD && signOk != StatusConst.UNKNOWN) {
				resp.setError("Não foi possível validar a assinatura digital: "
						+ getMessageByStatus(signOk));
			}
			return signOk;
		} else {
			int keyLength = 1024;

			if (resp.getCertdetails().containsKey("key_length0"))
				keyLength = Integer.parseInt(resp.getCertdetails().get(
						"key_length0"));

			byte[] origHash;
			if (keyLength < 2048)
				origHash = sha1;
			else
				origHash = sha256;

			int signOk = getCcServ().validateSign(assinatura, origHash,
					dtAssinatura, verificarLCRs);
			resp.setStatus(getMessageByStatus(signOk));
			if (signOk != StatusConst.GOOD && signOk != StatusConst.UNKNOWN) {
				resp.setError("Não foi possível validar a assinatura digital: "
						+ getMessageByStatus(signOk));
				return signOk;
			}

			boolean f = validateSignatureByPolicy(assinatura, null);
			if (!f) {
				resp.setError("Não foi possíel validar a assinatura com política");
				return signOk;
			}
			String policyName = recuperarNomePolitica(politica);
			if (policyName != null) {
				String pol[] = policyName.split(" v");
				resp.setPolicy(pol[0]);
				resp.setPolicyversion(pol[1]);
			}
			resp.setPolicyoid(politica);
			return StatusConst.GOOD;
		}
	}

	private String getMessageByStatus(int i) {
		Properties prop = getMessagens();

		String msg = null;
		if (prop != null)
			msg = prop.getProperty("StatusConst." + i);
		if (msg == null)
			msg = "code " + i;
		return msg;
	}

	private Properties getMessagens() {
		Properties prop = new Properties();
		try (InputStream in = getClass().getResourceAsStream(
				"/bluc.messages.properties")) {
			prop.load(in);
			return prop;
		} catch (Exception e) {
			return null;
		}
	}

	public byte[] attachContentsToPKCS7(byte[] content, byte[] detached)
			throws Exception {
		String policy = obtemPolitica(detached);
		byte[] origHash = null;
		byte[] res = null;
		if (policy == null) {
			byte[] contentSha1 = getCcServ().calcSha1(content);

			int sts = getCcServ().validateSign(detached, contentSha1, null,
					false);
			if (StatusConst.GOOD != sts && StatusConst.UNKNOWN != sts)
				throw new Exception("invalid signature: "
						+ getMessageByStatus(sts));

			CMSSignedData s = new CMSSignedData(new CMSProcessableByteArray(
					content), detached);

			Store certStore = s.getCertificates();
			Collection certList = certStore.getMatches(null);

//			for (Object next : certList) {
//				X509CertificateHolder holder = (X509CertificateHolder) next;
//				System.out.println(holder.getSubject().toString());
//			}

			SignerInformationStore signers = s.getSignerInfos();
			Collection c = signers.getSigners();
			Iterator it = c.iterator();
			int verified = 0;

			it.hasNext();
			SignerInformation signer = (SignerInformation) it.next();
			Collection certCollection = certStore.getMatches(signer.getSID());

			Iterator certIt = certCollection.iterator();
			X509CertificateHolder certificateHolder = (X509CertificateHolder) certIt
					.next();
			X509Certificate cert = new JcaX509CertificateConverter()
					.setProvider("BC").getCertificate(certificateHolder);

			signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider(
					"BC").build(certificateHolder));
			origHash = signer.getContentDigest();
			if (!Arrays.equals(origHash, contentSha1))
				throw new Exception("hashes doesn't match");
			Date signingTime = null;
			byte[] sign = signer.getSignature();

			res = composeBodySha1(sign, cert, certList, origHash, signingTime,
					content.length);
		} else {
			byte[] contentSha256 = getCcServ().calcSha256(content);

			// int sts = getCcServ().validateSign(detached, contentSha256, null,
			// false);
			// if (StatusConst.GOOD != sts && StatusConst.UNKNOWN != sts)
			// throw new Exception("invalid signature with policy: "
			// + getMessageByStatus(sts));

			CMSSignedData s = new CMSSignedData(new CMSProcessableByteArray(
					content), detached);

			Store certStore = s.getCertificates();
			Collection certList = certStore.getMatches(null);

//			for (Object next : certList) {
//				X509CertificateHolder holder = (X509CertificateHolder) next;
//				//System.out.println(holder.getSubject().toString());
//			}

			SignerInformationStore signers = s.getSignerInfos();
			Collection c = signers.getSigners();
			Iterator it = c.iterator();
			int verified = 0;

			it.hasNext();
			SignerInformation signer = (SignerInformation) it.next();
			Collection certCollection = certStore.getMatches(signer.getSID());

			Iterator certIt = certCollection.iterator();
			X509CertificateHolder certificateHolder = (X509CertificateHolder) certIt
					.next();
			X509Certificate cert = new JcaX509CertificateConverter()
					.setProvider("BC").getCertificate(certificateHolder);

			signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider(
					"BC").build(certificateHolder));
			origHash = signer.getContentDigest();
			// int t = signer.getSignedAttributes();
			if (!Arrays.equals(origHash, contentSha256))
				throw new Exception("hashes doesn't match");
			SignCompare signCompare = ccServ.extractSignCompare(detached);
			Date signingTime = signCompare.getSigningTime();
			byte[] sign = signer.getSignature();

			res = composeBodySha256(sign, cert, certList, origHash,
					signingTime, content.length);
		}

		Map<String, String> map = createBodyMap(res, content.length);

		byte[] envelope_1 = Base64.decode(map.get("envelope_1"));
		byte[] envelope_2 = Base64.decode(map.get("envelope_2"));

		byte[] attached = new byte[envelope_1.length + content.length
				+ envelope_2.length];
		System.arraycopy(envelope_1, 0, attached, 0, envelope_1.length);
		System.arraycopy(content, 0, attached, envelope_1.length,
				content.length);
		System.arraycopy(envelope_2, 0, attached, envelope_1.length
				+ content.length, envelope_2.length);

		int sts2 = getCcServ().validateSign(attached, origHash, null, false);
		if (StatusConst.GOOD != sts2 && StatusConst.UNKNOWN != sts2)
			throw new Exception("invalid attached signature: "
					+ getMessageByStatus(sts2));

		return attached;
	}

	public byte[] composeBodySha1(byte[] sign, X509Certificate c,
			Collection certCollection, byte[] origHash, Date signingTime,
			int attachSize) throws Exception {
		byte[] ret = null;

		int idSha = NDX_SHA1;
		List<AppSignedInfoEx> listAsiEx = new ArrayList<AppSignedInfoEx>();
		List<X509Certificate> chain = new ArrayList<X509Certificate>();

		byte[] certHash = getCcServ().calcSha1(c.getEncoded());

		AppSignedInfoEx asiEx = new AppSignedInfoEx(sign, origHash,
				signingTime, c, certHash, idSha);
		listAsiEx.add(asiEx);

		ret = serv1024.buildCms(listAsiEx, attachSize);
		// ret = buildCms(listAsiEx, certCollection, attachSize);

		return ret;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ittru.service.CCService#composeBodySha256(byte[],
	 * java.security.cert.X509Certificate, byte[], java.util.Date)
	 */
	public byte[] composeBodySha256(byte[] sign, X509Certificate c,
			Collection certCollection, byte[] origHash, Date signingTime,
			int attachSize) throws Exception {
		byte[] ret = null;

		int idSha = NDX_SHA256;
		List<AppSignedInfoEx> listAsiEx = new ArrayList<AppSignedInfoEx>();

		byte[] certHash = getCcServ().calcSha256(c.getEncoded());

		AppSignedInfoEx asiEx = new AppSignedInfoEx(sign, origHash,
				signingTime, c, certHash, idSha);
		listAsiEx.add(asiEx);

		ret = serv2048.buildCms(listAsiEx, attachSize);

		return ret;
	}

	private Map<String, String> createBodyMap(byte[] res, int contentSize) {
		Map<String, String> certMap = new HashMap<String, String>();

		int i = 0;
		for (; i < res.length; i++) {
			if (res[i] == (byte) 0xba) {
				boolean foundContent = true;
				for (int j = 0; j < contentSize; j++) {
					if (res[j + i] != (byte) 0xba) {
						foundContent = false;
						break;
					}
				}
				if (foundContent) {
					break;
				}
			}
		}
		int begin = 0;
		int end = i;
		byte[] record = new byte[end - begin];
		for (int z = 0; z < record.length; z++) {
			record[z] = res[begin + z];
		}
		String value = new String(Base64.encode(record));
		certMap.put("envelope_1", value);

		begin = i + contentSize;
		end = res.length;
		record = new byte[end - begin];
		for (int z = 0; z < record.length; z++) {
			record[z] = res[begin + z];
		}
		value = new String(Base64.encode(record));
		certMap.put("envelope_2", value);

		return certMap;
	}

	public CryptoService getCcServ() {
		return ccServ;
	}

	public void setCcServ(CryptoService ccServ) {
		this.ccServ = ccServ;
	}
}
