import SwiftUI

struct MessageBubbleView: View {
    let message: AppMessage

    var body: some View {
        HStack {
            if message.isOutgoing {
                Spacer(minLength: 48)
                outgoingBubble
            } else {
                incomingBubble
                Spacer(minLength: 48)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
    }

    private var outgoingBubble: some View {
        VStack(alignment: .trailing, spacing: 3) {
            Text(message.body)
                .font(.body)
                .foregroundColor(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Color.blue)
                .cornerRadius(18)

            HStack(spacing: 4) {
                Text(message.timestamp, style: .time)
                    .font(.caption2)
                    .foregroundColor(.secondary)

                statusIcon
            }
            .padding(.trailing, 4)

            if let error = message.errorMessage, message.sendStatus == .failed {
                Text("Error: \(error)")
                    .font(.caption2)
                    .foregroundColor(.red)
                    .padding(.trailing, 4)
            }
        }
    }

    private var incomingBubble: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(message.body)
                .font(.body)
                .foregroundColor(.primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Color(.systemGray5))
                .cornerRadius(18)

            Text(message.timestamp, style: .time)
                .font(.caption2)
                .foregroundColor(.secondary)
                .padding(.leading, 4)
        }
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch message.sendStatus {
        case .queued:
            Image(systemName: "clock")
                .font(.system(size: 10))
                .foregroundColor(.secondary)
        case .submitted:
            Image(systemName: "hourglass")
                .font(.system(size: 10))
                .foregroundColor(.orange)
        case .sent:
            if message.isDelivered {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 10))
                    .foregroundColor(.green)
            } else {
                Image(systemName: "checkmark")
                    .font(.system(size: 10))
                    .foregroundColor(.blue)
            }
        case .delivered:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 10))
                .foregroundColor(.green)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 10))
                .foregroundColor(.red)
        case .unknown:
            EmptyView()
        }
    }
}
