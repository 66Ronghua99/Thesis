package com.ronghua.caserver.service;

import com.ronghua.caserver.dao.CertMapper;
import com.ronghua.caserver.dao.CsrMapper;
import com.ronghua.caserver.entity.CertEntity;
import com.ronghua.caserver.entity.CsrEntity;
import com.ronghua.caserver.msgbody.SignRequest;
import com.ronghua.caserver.msgbody.SignRequestVerified;
import com.ronghua.caserver.msgbody.SignResponse;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x509.AlgorithmIdentifier;
import org.spongycastle.asn1.x509.Certificate;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.X509v3CertificateBuilder;
import org.spongycastle.crypto.params.AsymmetricKeyParameter;
import org.spongycastle.crypto.util.PrivateKeyFactory;
import org.spongycastle.operator.ContentSigner;
import org.spongycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.spongycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.operator.bc.BcRSAContentSignerBuilder;
import org.spongycastle.pkcs.PKCS10CertificationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CaService {
    @Autowired
    private CertMapper certDao;

    @Autowired
    private CsrMapper csrDao;

    @Autowired
    private MailAuthService mailAuthService;

    private final static int ERROR_TIME_EXCEEDED = 2;
    private final static int ERROR_CONFLICT = 1;
    private final static int SUCCESS = 0;
    private static  PrivateKey privateKey;
    private static X509Certificate certificate;
    private long bigInteger = 1;
    private final Lock lock = new ReentrantLock();
    static {
        System.out.println("initialization of cas");
        try {
            privateKey = getPrivateKeyFromKeyFile("ca.der");
            certificate = getCertFromKeyFile("ca.crt");
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | CertificateException e) {
            e.printStackTrace();
        }

    }

    public void accountVerify(SignRequest request){
        String code = mailAuthService.sendAuthMail(request.getUsername());
        CsrEntity entity = new CsrEntity();
        entity.setCode(code.toLowerCase(Locale.ROOT));
        entity.setUsername(request.getUsername());
        entity.setEncodedCsr(request.getEncodedCsr());
        entity.setTimeMillis(System.currentTimeMillis());
        csrDao.insertCsr(entity);
    }

    @Async("certificateExecutor")
    public Future<SignResponse> signCertificate(SignRequestVerified request){
        System.out.println("Service is called");
        SignResponse response =  new SignResponse();
        CsrEntity entity = verifyAndGetCsr(request, response);
        if(response.getErrorCode() != SUCCESS)
            return new AsyncResult<>(response);
        PKCS10CertificationRequest csr = null;
        X509Certificate crt = null;
        try {
            csr = getCsrFromBase64(entity.getEncodedCsr());
            crt = sign(csr, privateKey);
            recordCert(crt, request.getUsername());
            response.setEncodedCrt(Base64.getEncoder().encodeToString(crt.getEncoded()));
            response.setUsername(request.getUsername());
            response.setErrorCode(0);
        } catch (IOException | OperatorCreationException | CertificateException e) {
            e.printStackTrace();
        }
        return new AsyncResult<>(response);
    }

    private CsrEntity verifyAndGetCsr(SignRequestVerified request, SignResponse response) {
        List<CsrEntity> csrEntities =  csrDao.getCsrsByNameAndCode(request.getUsername(), request.getCode().toLowerCase(Locale.ROOT));
        if(csrEntities.size() != 1){
            response.setError("Something wrong with your code or their is a code conflict");
            response.setErrorCode(ERROR_CONFLICT);
            return null;
        }
        csrDao.deleteCsrByNameAndCode(request.getUsername(), request.getCode());
        CsrEntity entity = csrEntities.get(0);
        if(entity.getTimeMillis() + 300*1000 < System.currentTimeMillis()){
            response.setErrorCode(ERROR_TIME_EXCEEDED);
            response.setError("time exceed");
            return entity;
        }
        response.setErrorCode(SUCCESS);
        return entity;
    }

    private void recordCert(X509Certificate crt, String username) throws CertificateEncodingException {
        CertEntity entity = new CertEntity();
        Base64.Encoder encoder = Base64.getEncoder();
        entity.setEncodedCert(encoder.encodeToString(crt.getEncoded()));
        entity.setTimeMillis(System.currentTimeMillis());
        entity.setUsername(username);
        certDao.insertCert(entity);
    }

    public CertEntity getCertByName(String username){
        return certDao.getCertByName(username);
    }

    public void deleteInvalidCert(){
        certDao.deleteAllInvalid(System.currentTimeMillis());
    }

    public void deleteCertByName(String username){
        certDao.deleteCertByName(username);
    }

    private PKCS10CertificationRequest getCsrFromBase64(String code) throws IOException {
        System.out.println(code);
        byte[] csrStr = Base64.getDecoder().decode(code);
        return new PKCS10CertificationRequest(csrStr);
    }

    private X509Certificate sign(PKCS10CertificationRequest inputCSR, PrivateKey caPrivate)
            throws  IOException, OperatorCreationException, CertificateException {

        AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder()
                .find("SHA1withRSA");
        AlgorithmIdentifier digAlgId = new DefaultDigestAlgorithmIdentifierFinder()
                .find(sigAlgId);

        AsymmetricKeyParameter foo = PrivateKeyFactory.createKey(caPrivate
                .getEncoded());
        SubjectPublicKeyInfo keyInfo = inputCSR.getSubjectPublicKeyInfo();

//        PKCS10CertificationRequest pk10Holder = new PKCS10CertificationRequest(inputCSR);
        //in newer version of BC such as 1.51, this is
        //PKCS10CertificationRequest pk10Holder = new PKCS10CertificationRequest(inputCSR);
        X509v3CertificateBuilder myCertificateGenerator = null;
        synchronized (lock) {
            myCertificateGenerator = new X509v3CertificateBuilder(
                    new X500Name("CN=issuer"), new BigInteger(String.valueOf(bigInteger)), new Date(
                    System.currentTimeMillis()), new Date(
                    System.currentTimeMillis() + 60 * 1000),
                    inputCSR.getSubject(), keyInfo);
            bigInteger++;
            lock.notifyAll();
        }

        ContentSigner sigGen = new BcRSAContentSignerBuilder(sigAlgId, digAlgId)
                .build(foo);

        X509CertificateHolder holder = myCertificateGenerator.build(sigGen);
        Certificate eeX509CertificateStructure = holder.toASN1Structure();
        //in newer version of BC such as 1.51, this is
        //org.spongycastle.asn1.x509.Certificate eeX509CertificateStructure = holder.toASN1Structure();

        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        // Read Certificate
        InputStream is = new ByteArrayInputStream(eeX509CertificateStructure.getEncoded());
        X509Certificate theCert = (X509Certificate) cf.generateCertificate(is);
        is.close();

        return theCert;
        //return null;
    }

    private static PrivateKey getPrivateKeyFromKeyFile(String filename) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] bytes = getBytesFromFile(filename);
//        String privatePem = new String(bytes);
//        privatePem = privatePem.replace("-----BEGIN RSA PRIVATE KEY-----\n", "");
//        privatePem = privatePem.replace("-----END RSA PRIVATE KEY-----", "");
//        byte [] decoded = Base64.decode(privatePem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }


    private static X509Certificate getCertFromKeyFile(String filename) throws IOException, CertificateException {
        String path = ResourceUtils.getURL("classpath:"+filename).getPath();
        File file = new File(path);
        FileInputStream fis = new FileInputStream(file);
        CertificateFactory f = CertificateFactory.getInstance("X.509");
        return (X509Certificate)f.generateCertificate(fis);
    }

    private static byte[] getBytesFromFile(String filename) throws IOException {
        String path = ResourceUtils.getURL("classpath:"+filename).getPath();
        File file = new File(path);
        FileInputStream fis = new FileInputStream(file);
        DataInputStream dis = new DataInputStream(fis);
        byte[] bytes = new byte[(int)file.length()];
        dis.readFully(bytes);
        dis.close();
        return bytes;
    }
}
