package net.prsv.terminality;

import com.sun.jna.LastErrorException;

import java.io.IOException;

final class PosixInputProbe implements UTKeyReader.InputStatusProbe {

    private final PosixLibC lib;
    private final int fileDescriptor;

    PosixInputProbe(PosixLibC lib, int fileDescriptor) {
        if (lib == null) {
            throw new NullPointerException("lib");
        }
        this.lib = lib;
        this.fileDescriptor = fileDescriptor;
    }

    @Override
    public UTKeyReader.InputStatus poll() throws IOException {
        PosixLibC.PollFd descriptor = new PosixLibC.PollFd();
        descriptor.fd = fileDescriptor;
        descriptor.events = PosixLibC.POLLIN;

        int result;
        while (true) {
            try {
                result = lib.poll(descriptor, new PosixLibC.NfdsT(1), 0);
                break;
            } catch (LastErrorException e) {
                if (e.getErrorCode() != PosixLibC.EINTR) {
                    throw new IOException("Failed to poll terminal input", e);
                }
            }
        }
        if (result < 0) {
            throw new IOException("Failed to poll terminal input; poll returned " + result);
        }
        if (result == 0) {
            return UTKeyReader.InputStatus.UNAVAILABLE;
        }

        int events = Short.toUnsignedInt(descriptor.revents);
        if ((events & PosixLibC.POLLNVAL) != 0) {
            throw new IOException("Failed to poll terminal input; file descriptor is invalid");
        }
        if ((events & PosixLibC.POLLERR) != 0) {
            throw new IOException("Failed to poll terminal input; input error");
        }
        if ((events & PosixLibC.POLLIN) != 0) {
            return UTKeyReader.InputStatus.DATA;
        }
        if ((events & PosixLibC.POLLHUP) != 0) {
            return UTKeyReader.InputStatus.EOF;
        }
        return UTKeyReader.InputStatus.UNAVAILABLE;
    }
}
