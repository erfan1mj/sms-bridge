import Foundation
import Security
import CryptoKit

final class TLSPinningDelegate: NSObject, URLSessionDelegate {
    private var pinnedCertSha256: String?

    func setPinnedFingerprint(_ fingerprint: String?) {
        self.pinnedCertSha256 = fingerprint?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // Must have a pinned fingerprint configured (from QR code or secure Keychain)
        guard let expectedFingerprint = pinnedCertSha256, !expectedFingerprint.isEmpty else {
            print("[TLS] Error: No pinned certificate fingerprint configured. Rejecting connection.")
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        // Evaluate server certificate chain
        var secResult: SecTrustResultType = .invalid
        let status = SecTrustEvaluate(serverTrust, &secResult)

        // Extract leaf server certificate
        let certCount = SecTrustGetCertificateCount(serverTrust)
        guard certCount > 0, let serverCert = SecTrustGetCertificateAtIndex(serverTrust, 0) else {
            print("[TLS] Error: Unable to extract server certificate from trust chain.")
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        let certData = SecCertificateCopyData(serverCert) as Data
        let digest = SHA256.hash(data: certData)
        let actualFingerprint = digest.map { String(format: "%02X", $0) }.joined(separator: ":")

        // Compare actual SHA-256 against pinned SHA-256
        let normalizedExpected = expectedFingerprint.replacingOccurrences(of: " ", with: "")
        let normalizedActual = actualFingerprint.replacingOccurrences(of: " ", with: "")

        if normalizedActual.caseInsensitiveCompare(normalizedExpected) == .orderedSame {
            print("[TLS] Handshake Success: Server certificate matches pinned SHA-256 fingerprint (\(actualFingerprint))")
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            print("[TLS] SECURITY ERROR: Certificate fingerprint mismatch!")
            print("[TLS] Expected: \(normalizedExpected)")
            print("[TLS] Received: \(normalizedActual)")
            // Reject immediately - never disable TLS certificate verification
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
