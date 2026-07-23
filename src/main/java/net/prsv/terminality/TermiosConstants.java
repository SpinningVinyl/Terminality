package net.prsv.terminality;

import com.sun.jna.Platform;

final class TermiosConstants {

    static final TermiosConstants LINUX = new TermiosConstants(
            1, 2, 8, 64, 0x020, 1024, 2048, 256, 0x08000, 1, 6, 5);

    static final TermiosConstants DARWIN = new TermiosConstants(
            0x00000080, 0x00000100, 0x00000008, 0x00000010,
            0x00000020, 0x00000200, 0x00000800, 0x00000100,
            0x00000400, 0x00000001, 16, 17);

    final int isig;
    final int icanon;
    final int echo;
    final int echonl;
    final int istrip;
    final int ixon;
    final int ixany;
    final int icrnl;
    final int iexten;
    final int opost;
    final int vmin;
    final int vtime;

    private TermiosConstants(int isig, int icanon, int echo, int echonl,
                             int istrip, int ixon, int ixany, int icrnl,
                             int iexten, int opost, int vmin, int vtime) {
        this.isig = isig;
        this.icanon = icanon;
        this.echo = echo;
        this.echonl = echonl;
        this.istrip = istrip;
        this.ixon = ixon;
        this.ixany = ixany;
        this.icrnl = icrnl;
        this.iexten = iexten;
        this.opost = opost;
        this.vmin = vmin;
        this.vtime = vtime;
    }

    static TermiosConstants current() {
        return Platform.isMac() ? DARWIN : LINUX;
    }
}
