using System;
using System.Windows.Forms;

class CapeFilePicker {
    [STAThread]
    static int Main(string[] args) {
        try {
            OpenFileDialog dialog = new OpenFileDialog();
            dialog.Title = "Select Cape File (*.webp, *.gif, *.png, *.jpg, *.jpeg)";
            dialog.Filter = "Cape Images (*.webp;*.gif;*.png;*.jpg;*.jpeg)|*.webp;*.gif;*.png;*.jpg;*.jpeg|All Files (*.*)|*.*";
            dialog.Multiselect = false;
            dialog.CheckFileExists = true;
            dialog.RestoreDirectory = true;

            if (args.Length > 0 && !string.IsNullOrEmpty(args[0])) {
                try {
                    if (System.IO.Directory.Exists(args[0])) {
                        dialog.InitialDirectory = args[0];
                    }
                } catch {}
            }

            DialogResult res = dialog.ShowDialog();
            if (res == DialogResult.OK && !string.IsNullOrEmpty(dialog.FileName)) {
                Console.Out.WriteLine(dialog.FileName);
                Console.Out.Flush();
                return 0;
            }
        } catch (Exception ex) {
            Console.Error.WriteLine(ex.Message);
            return 1;
        }
        return 0;
    }
}
